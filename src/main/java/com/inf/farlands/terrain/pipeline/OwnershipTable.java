package com.inf.farlands.terrain.pipeline;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.decorationFiller.DecorationClaim;
import com.inf.farlands.util.map.Long2ObjectStripedMap;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 单元所有权表。两层，并发载体不同，理由在下面各节。
 *
 * <p><b>主线程层</b>：section 级的认领、地形的高度图写权、以及简单 chunk 级的认领。载体是普通字段
 * 加分段表，不加锁，因为调度器只在主线程改它。认领的身份用的是 chunk 的记账身份
 * {@link com.inf.farlands.serialize.CounterIdentity}，不是坐标：同一个坐标的前一代与后一代是两份
 * 所有权，按坐标共键会让新的一代承接旧一代的在途作业。
 *
 * <p><b>锁无层</b>：光照与装饰共用的正负 2 的 25 格域。它必须能从池线程取，因为光照派发是自驱的：
 * 入队即唤醒池，池上认领、冲突重排。这条自驱是加载路径的承重结构，主线程忙循环期间在途光照任务仍要
 * 完成，否则 LIGHT future 到不了、FULL 不可达、保存死等。这一层是本类唯一的例外，其余派生结构都归
 * 主线程。
 *
 * <p>域认领的规则：按 chunk 键排序取以防死锁；任一格失败即回滚已取的全部格并返回 0；持有者编号非 0
 * 才表示取到；放域必须带回编号，只清自己名下的格；装饰正在写这一片时让路，装饰侧则豁免自己刚认领的
 * 九格。
 */
public final class OwnershipTable {

    // ---- 主线程层 ----

    /** 身份到已认领的 section 集合。 */
    private static final Long2ObjectStripedMap<IntOpenHashSet> SECTIONS = new Long2ObjectStripedMap<>(1 << 10);

    /** 身份到持有该 chunk 高度图写权的作业编号。0 表示空闲。 */
    private static final Long2ObjectStripedMap<Long> HEIGHTMAP = new Long2ObjectStripedMap<>(1 << 10);

    /** 身份到持有该 chunk 的作业编号，用于 CARVERS 这类只需独占整块的作业。 */
    private static final Long2ObjectStripedMap<Long> CHUNK = new Long2ObjectStripedMap<>(1 << 10);

    /** 作业编号发号器。0 保留为空闲。 */
    private static final AtomicLong OWNER_SEQ = new AtomicLong();

    /** 领一个作业编号。主线程调用。 */
    public static long newOwner() {
        return OWNER_SEQ.incrementAndGet();
    }

    /**
     * 认领一个 section。已占或身份为 0 返回假。
     *
     * <p>预留入口，当前无调用点：作业粒度是 chunk 级，段级那一层留给把生成拆成逐段作业时用。
     */
    public static boolean claimSection(long identity, int sectionY, long owner) {
        if (identity == 0L || owner == 0L) {
            return false;
        }
        IntOpenHashSet set = SECTIONS.computeIfAbsent(identity, k -> new IntOpenHashSet());
        return set.add(sectionY);
    }

    /** 释放一个 section。只清 owner 名下那一格。预留入口，当前无调用点。 */
    public static void releaseSection(long identity, int sectionY, long owner) {
        if (identity == 0L) {
            return;
        }
        IntOpenHashSet set = SECTIONS.get(identity);
        if (set != null) {
            set.remove(sectionY);
        }
    }

    /** 认领该 chunk 的地形高度图写权。已被占返回假。主线程调用。 */
    public static boolean claimHeightmap(long identity, long owner) {
        if (identity == 0L || owner == 0L) {
            return false;
        }
        return HEIGHTMAP.putIfAbsent(identity, owner) == null;
    }

    public static void releaseHeightmap(long identity, long owner) {
        if (identity == 0L) {
            return;
        }
        // 读后删：这一层归主线程独占，不存在竞态；Long2ObjectStripedMap 只有单参 remove。
        Long current = HEIGHTMAP.get(identity);
        if (current != null && current == owner) {
            HEIGHTMAP.remove(identity);
        }
    }

    /** 认领整个 chunk。已被占返回假。主线程调用。 */
    public static boolean claimChunk(long identity, long owner) {
        if (identity == 0L || owner == 0L) {
            return false;
        }
        return CHUNK.putIfAbsent(identity, owner) == null;
    }

    public static void releaseChunk(long identity, long owner) {
        if (identity == 0L) {
            return;
        }
        Long current = CHUNK.get(identity);
        if (current != null && current == owner) {
            CHUNK.remove(identity);
        }
    }

    /** 该 chunk 是否还被人独占着，供出队与收尾判重。 */
    public static boolean chunkClaimed(long identity) {
        return identity != 0L && CHUNK.get(identity) != null;
    }

    /** 该 section 是否还被人认领着。预留入口，当前无调用点。 */
    public static boolean sectionClaimed(long identity, int sectionY) {
        if (identity == 0L) {
            return false;
        }
        IntOpenHashSet set = SECTIONS.get(identity);
        return set != null && set.contains(sectionY);
    }

    /** chunk 卸载时把该身份的所有权一并丢掉，防随探索单调增长。 */
    public static void clear(long identity) {
        if (identity != 0L) {
            SECTIONS.remove(identity);
            HEIGHTMAP.remove(identity);
            CHUNK.remove(identity);
        }
    }

    /** 停服时清空主线程层。域层随 level 的引擎实例消亡，不在这里清。 */
    public static void clearAll() {
        SECTIONS.clear();
        HEIGHTMAP.clear();
        CHUNK.clear();
    }

    // ---- 锁无层：光照与装饰的 25 格域 ----

    /** 域半径。光照与空 section 检查最多涉到正负 1，取 2 是为了覆盖天光初始化读 3 乘 3 邻居。 */
    private static final int RADIUS = 2;

    private static final int KEY_COUNT = (RADIUS * 2 + 1) * (RADIUS * 2 + 1);

    /**
     * 域格：键是 ChunkPos.pack，值是持有者编号，0 表示空闲。
     *
     * <p>按维度分实例而不是共用一张静态表：`ChunkPos.pack` 不带维度，共用会让两个维度的域互相挡。
     * 实例由该 level 的引擎持有，随引擎消亡，所以不需要一个全局的清空点。
     */
    private final Long2ObjectStripedMap<AtomicLong> domain = new Long2ObjectStripedMap<>(1 << 8);

    /** 本锁属于哪个维度。装饰认领按维度分表，判据要用同一个键。 */
    private final ResourceKey<Level> dimension;

    /** 光照任务侧复用的键缓冲：那个热路径由 consumerActive 单例保证同一时刻只有一个调用者。 */
    private final long[] keyBuf = new long[KEY_COUNT];

    /** 失配日志限流。 */
    private static final AtomicLong MISMATCH_LOGGED = new AtomicLong();

    public OwnershipTable(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    /**
     * 光照任务侧取域。只在 drainLight 单例里调用，复用键缓冲。
     *
     * @return 持有者编号，0 表示失败
     */
    public long tryLock(int chunkX, int chunkZ) {
        return this.tryLock(chunkX, chunkZ, this.keyBuf, false);
    }

    /**
     * 装饰侧取域。与 {@link #tryLock} 是同一份域，三点不同：
     *
     * <p>一，每次调用自备键缓冲，因为装饰会在两个池线程上并发取，复用同一份会互相覆盖，回滚时读错格。
     * 二，豁免自己刚认领的九格，否则装饰拿自己的认领挡自己，表现为永远提交不出去。
     * 三，失败即放弃本次，调用方留表下一轮，不等待。
     *
     * @return 持有者编号，0 表示失败
     */
    public long tryLockDomain(int chunkX, int chunkZ) {
        return this.tryLock(chunkX, chunkZ, new long[KEY_COUNT], true);
    }

    private long tryLock(int chunkX, int chunkZ, long[] keys, boolean exemptOwnWriteDomain) {
        int n = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                keys[n++] = ChunkPos.pack(chunkX + dx, chunkZ + dz);
            }
        }
        Arrays.sort(keys, 0, n);
        long owner = OWNER_SEQ.incrementAndGet();
        int acquired = 0;
        for (int i = 0; i < n; i++) {
            if (DecorationClaim.isClaimed(this.dimension, keys[i])
                    && !(exemptOwnWriteDomain && isOwnWriteDomain(keys[i], chunkX, chunkZ))) {
                this.rollback(keys, acquired, owner);
                return 0L;
            }
            AtomicLong lock = this.domain.computeIfAbsent(keys[i], k -> new AtomicLong());
            if (lock.compareAndSet(0L, owner)) {
                acquired++;
            } else {
                this.rollback(keys, acquired, owner);
                return 0L;
            }
        }
        return owner;
    }

    /** 回滚已获取的前 acquired 个域格。按持有者 CAS，失败即说明该格已被别人取走，记一次失配。 */
    private void rollback(long[] keys, int acquired, long owner) {
        for (int j = 0; j < acquired; j++) {
            AtomicLong held = this.domain.get(keys[j]);
            if (held != null) {
                release(held, owner, keys[j]);
            }
        }
    }

    /** 该格是否落在中心的加减 1 写域内，即装饰自己刚认领的那九格。 */
    private static boolean isOwnWriteDomain(long key, int centerX, int centerZ) {
        int dx = Math.abs(ChunkPos.getX(key) - centerX);
        int dz = Math.abs(ChunkPos.getZ(key) - centerZ);
        return dx <= DecorationClaim.WRITE_RADIUS && dz <= DecorationClaim.WRITE_RADIUS;
    }

    /** 释放中心 25 格，只清 owner 名下的格。 */
    public void unlock(int chunkX, int chunkZ, long owner) {
        if (owner == 0L) {
            return;
        }
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                long key = ChunkPos.pack(chunkX + dx, chunkZ + dz);
                AtomicLong lock = this.domain.get(key);
                if (lock != null) {
                    release(lock, owner, key);
                }
            }
        }
    }

    /** 清格。CAS 失败即说明这一格已不属于本次持有者，按限流打成一条日志。 */
    private static void release(AtomicLong lock, long owner, long key) {
        if (lock.compareAndSet(owner, 0L)) {
            return;
        }
        if (MISMATCH_LOGGED.getAndIncrement() < 20) {
            InfsFarlands.LOGGER.info("FLOWNER mismatch key={},{} owner={} cur={}",
                    ChunkPos.getX(key), ChunkPos.getZ(key), owner, lock.get());
        }
    }
}
