package com.inf.farlands.terrain.pipeline;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.FarlandsTick;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.debug.TicketFlowProbe;
import com.inf.farlands.util.map.Long2ObjectStripedMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 六相的所有权表：认领、放域、查询，票与所有权成对。
 *
 * <p>相与粒度：BIOMES、TERRAIN、SURFACE 段级；CARVERS、DECORATED、LIGHTED chunk 级。TERRAIN 另持
 * 该 chunk 的高度图写权，所以它同时占段与 chunk 两条。LIGHTED 另有一个二十五格的域锁，与装饰的九格
 * 豁免是同一张表的两相。
 *
 * <p>纪律：按键排序逐个取，任一失败回滚全部已取项并返回 0；每格记持有者编号，放域必须带回编号，
 * 只清属于自己的格。主线程单点认领不会死锁；排序与回滚保留，因为多个 chunk 的域会相交，失败必须干净。
 *
 * <p>票：chunk 级相在取成功时加 `GEN_WORK_TICKET`，放时撤，都在主线程。段级相不铺票，票由 chunk
 * 级相覆盖。
 */
public final class OwnershipTable {

    /** 相编号，1 起，0 留作空闲。 */
    public static final int BIOMES = 1;
    public static final int TERRAIN = 2;
    public static final int SURFACE = 3;
    public static final int CARVERS = 4;
    public static final int DECORATED = 5;
    public static final int LIGHTED = 6;

    private static final int PHASES = 7;

    /** 取域编号，全局递增；0 表示空闲。 */
    private static final AtomicLong OWNER_SEQ = new AtomicLong();

    /** 段级相：chunkKey 映射到段号到持有者编号。 */
    @SuppressWarnings("unchecked")
    private static final Long2ObjectStripedMap<Long2ObjectStripedMap<AtomicLong>>[] SECTION_CELLS =
            new Long2ObjectStripedMap[PHASES];

    /** 段级相的占用计数：chunkKey 到该相已占段数，供 O(1) 判「该 chunk 有没有生成在途」。 */
    @SuppressWarnings("unchecked")
    private static final Long2ObjectStripedMap<AtomicLong>[] SECTION_COUNTS = new Long2ObjectStripedMap[PHASES];

    /** chunk 级相：chunkKey 到持有者编号。 */
    @SuppressWarnings("unchecked")
    private static final Long2ObjectStripedMap<AtomicLong>[] CHUNK_CLAIMS = new Long2ObjectStripedMap[PHASES];

    /** 光照任务与装饰域的二十五格锁，键是 chunkKey。 */
    private static final Long2ObjectStripedMap<AtomicLong> LIGHT_TASK = new Long2ObjectStripedMap<>(1 << 8);

    /**
     * 诊断：取域成功的 owner 到 {@code 站点@tick@中心} 的归属表。
     *
     * <p>放域时删条目，所以表里留下的是当下仍被持有的域。条目数本身就是泄漏的读数：正常轮转时它等于同时
     * 在途的取域数，只有个位数；泄漏时它会随会话增长。
     *
     * <p>不按时间淘汰，因为要看的就是这些条目；只在到顶后停记并计一次截断，让「这一格没有标签」与「截断
     * 之后没记上」分开。
     */
    private static final Map<Long, String> TAGS = new ConcurrentHashMap<>();

    /** 归属表的条目上限。 */
    private static final int TAG_LIMIT = 512;

    /** 到顶之后被压掉的归属记录数。 */
    private static final AtomicLong TAG_TRUNCATED = new AtomicLong();

    /** 诊断：放域调用次数，与其中真正清掉的格数并排，分开「放了但一格没清」与「压根没放」。 */
    private static final AtomicLong UNLOCK_ATTEMPTS = new AtomicLong();

    private static final AtomicLong UNLOCK_CLEARED = new AtomicLong();

    /** 诊断：一格都没清到的放域次数。中心或 owner 与取锁时那一对不匹配时就是这一支。 */
    private static final AtomicLong UNLOCK_ZERO = new AtomicLong();

    /** 已逐条打过的零命中 owner。owner 是唯一编号，天然不重复去重。 */
    private static final Set<Long> ZERO_LOGGED = ConcurrentHashMap.newKeySet();

    /** 零命中逐条输出的上限。留下的是先出现的那批，总量的口径是 {@link #UNLOCK_ZERO}。 */
    private static final int ZERO_LOG_LIMIT = 256;

    /** 诊断：取域成功后记一条归属。{@code site} 是调用方，tick 与中心供与别的读数对齐。 */
    private static void tag(long owner, String site, int centerX, int centerZ) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        if (TAGS.size() >= TAG_LIMIT) {
            TAG_TRUNCATED.incrementAndGet();
            return;
        }
        TAGS.put(owner, site + '@' + FarlandsTick.getNow() + '@' + centerX + ',' + centerZ);
    }

    /** 诊断：归属表与放域计数压成一行，取走本窗的两个累计计数。 */
    public static String lockSummary() {
        return "unlockAtt=" + UNLOCK_ATTEMPTS.getAndSet(0L)
                + " unlockCleared=" + UNLOCK_CLEARED.getAndSet(0L)
                + " clearZero=" + UNLOCK_ZERO.getAndSet(0L)
                + " tags=" + TAGS.size()
                + " tagTrunc=" + TAG_TRUNCATED.get();
    }

    static {
        for (int phase = 1; phase < PHASES; phase++) {
            SECTION_CELLS[phase] = new Long2ObjectStripedMap<>(1 << 12);
            SECTION_COUNTS[phase] = new Long2ObjectStripedMap<>(1 << 12);
            CHUNK_CLAIMS[phase] = new Long2ObjectStripedMap<>(1 << 12);
        }
    }

    private OwnershipTable() {
    }

    /** 段级认领：取该段。成功返回持有者编号，失败返回 0。 */
    public static long tryClaimSection(int phase, long chunkKey, int sectionY) {
        Long2ObjectStripedMap<AtomicLong> perChunk = SECTION_CELLS[phase]
                .computeIfAbsent(chunkKey, k -> new Long2ObjectStripedMap<>(1 << 6));
        AtomicLong cell = perChunk.computeIfAbsent(sectionY, k -> new AtomicLong());
        long owner = OWNER_SEQ.incrementAndGet();
        if (!cell.compareAndSet(0L, owner)) {
            return 0L;
        }
        SECTION_COUNTS[phase].computeIfAbsent(chunkKey, k -> new AtomicLong()).incrementAndGet();
        return owner;
    }

    /** 段级放域：只清带回来的那个编号。 */
    public static void releaseSection(int phase, long chunkKey, int sectionY, long owner) {
        if (owner == 0L) {
            return;
        }
        Long2ObjectStripedMap<AtomicLong> perChunk = SECTION_CELLS[phase].get(chunkKey);
        if (perChunk == null) {
            return;
        }
        AtomicLong cell = perChunk.get(sectionY);
        if (cell != null && cell.compareAndSet(owner, 0L)) {
            AtomicLong count = SECTION_COUNTS[phase].get(chunkKey);
            if (count != null) {
                count.decrementAndGet();
            }
        }
    }

    /** 段级查询：该段有没有人占。 */
    public static boolean isSectionClaimed(int phase, long chunkKey, int sectionY) {
        Long2ObjectStripedMap<AtomicLong> perChunk = SECTION_CELLS[phase].get(chunkKey);
        if (perChunk == null) {
            return false;
        }
        AtomicLong cell = perChunk.get(sectionY);
        return cell != null && cell.get() != 0L;
    }

    /**
     * chunk 级认领：取该 chunk，成功时铺 `GEN_WORK_TICKET`。主线程调用。
     *
     * @return 持有者编号，0 表示已被占
     */
    public static long tryClaimChunk(ServerLevel level, int phase, ChunkPos pos) {
        AtomicLong cell = CHUNK_CLAIMS[phase].computeIfAbsent(pos.pack(), k -> new AtomicLong());
        long owner = OWNER_SEQ.incrementAndGet();
        boolean ok = cell.compareAndSet(0L, owner);
        TicketFlowProbe.claim(phase, pos.pack(), owner, ok);
        if (!ok) {
            return 0L;
        }
        GenQueue.addGenTicket(level, pos);
        return owner;
    }

    /** 释放该相的 chunk 级认领，只清带回来的编号，并撤票。主线程调用。 */
    public static void releaseChunk(ServerLevel level, int phase, ChunkPos pos, long owner) {
        if (owner == 0L) {
            return;
        }
        AtomicLong cell = CHUNK_CLAIMS[phase].get(pos.pack());
        boolean ok = cell != null && cell.compareAndSet(owner, 0L);
        TicketFlowProbe.release(phase, pos.pack(), owner, ok);
        if (ok) {
            GenQueue.removeGenTicket(level, pos);
        }
    }

    /** chunk 级查询：该相有没有人占该 chunk。 */
    public static boolean isChunkClaimed(int phase, long chunkKey) {
        AtomicLong cell = CHUNK_CLAIMS[phase].get(chunkKey);
        return cell != null && cell.get() != 0L;
    }

    /**
     * 光照任务的二十五格纯互斥域锁。
     *
     * @return 持有者编号，0 表示失败
     */
    public static long tryLockLightTask(ServerLevel level, int centerX, int centerZ) {
        return tryLockCells(level, centerX, centerZ, null);
    }

    /**
     * 装饰侧的二十五格域锁，带九格豁免。
     *
     * @param centerChunkKey 装饰中心的 chunk 键，九格豁免的范围是它 ±1
     * @return 持有者编号，0 表示失败
     */
    public static long tryLockDomain(ServerLevel level, int centerX, int centerZ, long centerChunkKey) {
        return tryLockCells(level, centerX, centerZ, Long.valueOf(centerChunkKey));
    }

    private static long tryLockCells(ServerLevel level, int centerX, int centerZ, Long centerChunkKey) {
        long[] keys = new long[25];
        int n = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                keys[n++] = ChunkPos.pack(centerX + dx, centerZ + dz);
            }
        }
        Arrays.sort(keys, 0, n);
        long owner = OWNER_SEQ.incrementAndGet();
        // 取到一格记一格。九格豁免在排序之后才跳过，所以已取集合不是数组的前缀：按数量推位置会
        // 漏放跳过格之后那些已取的格，漏掉的格属于一个再也不会来放域的 owner，从此永久占用。
        long[] taken = new long[n];
        int acquired = 0;
        for (int i = 0; i < n; i++) {
            if (isOwnWriteDomain(keys[i], centerChunkKey)) {
                continue;
            }
            // 装饰正在写这一片：让路，回滚已取项。
            if (isChunkClaimed(DECORATED, keys[i])) {
                rollbackCells(taken, acquired, owner);
                return 0L;
            }
            AtomicLong cell = LIGHT_TASK.computeIfAbsent(keys[i], k -> new AtomicLong());
            if (cell.compareAndSet(0L, owner)) {
                taken[acquired++] = keys[i];
            } else {
                rollbackCells(taken, acquired, owner);
                return 0L;
            }
        }
        tag(owner, centerChunkKey == null ? "light" : "deco", centerX, centerZ);
        return owner;
    }

    /** 该格是否落在中心的 ±1 写域内，即装饰自己刚认领的那九格。 */
    private static boolean isOwnWriteDomain(long key, Long centerChunkKey) {
        if (centerChunkKey == null) {
            return false;
        }
        int dx = Math.abs(ChunkPos.getX(key) - ChunkPos.getX(centerChunkKey));
        int dz = Math.abs(ChunkPos.getZ(key) - ChunkPos.getZ(centerChunkKey));
        return dx <= 1 && dz <= 1;
    }

    /** 回滚：只清本次真的取到的那几格，按记录的键而不是按数量推出来的前缀。 */
    private static void rollbackCells(long[] taken, int acquired, long owner) {
        for (int j = 0; j < acquired; j++) {
            AtomicLong held = LIGHT_TASK.get(taken[j]);
            if (held != null) {
                held.compareAndSet(owner, 0L);
            }
        }
    }

    /** 放域：清中心二十五格中属于该编号的格。 */
    public static void unlockCells(int centerX, int centerZ, long owner) {
        if (owner == 0L) {
            return;
        }
        int cleared = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                AtomicLong cell = LIGHT_TASK.get(ChunkPos.pack(centerX + dx, centerZ + dz));
                if (cell != null && cell.compareAndSet(owner, 0L)) {
                    cleared++;
                }
            }
        }
        String tag = TAGS.remove(owner);
        if (FarlandsConfig.logScheduler) {
            UNLOCK_ATTEMPTS.incrementAndGet();
            UNLOCK_CLEARED.addAndGet(cleared);
            if (cleared == 0) {
                UNLOCK_ZERO.incrementAndGet();
                if (ZERO_LOGGED.size() < ZERO_LOG_LIMIT && ZERO_LOGGED.add(owner)) {
                    InfsFarlands.LOGGER.info("FLCLEARZERO owner={} center={},{} tag={}",
                            owner, centerX, centerZ, tag == null ? "none" : tag);
                }
            }
        }
    }

    /** 该 chunk 是否有生成侧的在途写者：前三相任一有段被占，或 TERRAIN 的 chunk 级写权被占。 */
    public static boolean anyGenClaim(long chunkKey) {
        if (isChunkClaimed(TERRAIN, chunkKey)) {
            return true;
        }
        for (int phase = BIOMES; phase <= SURFACE; phase++) {
            AtomicLong count = SECTION_COUNTS[phase].get(chunkKey);
            if (count != null && count.get() > 0L) {
                return true;
            }
        }
        return false;
    }

    /** 该 chunk 是否被某次装饰的写域覆盖。 */
    public static boolean isDecoratedClaimed(long chunkKey) {
        return isChunkClaimed(DECORATED, chunkKey);
    }

    /**
     * 诊断：该 chunk 的 LIGHTED 相认领编号，0 表示空闲或从未被认领。
     *
     * <p>{@link #isLightedClaimed} 看的是二十五格的域锁，与这一栏不是同一张表：域锁挡的是同片区的写入，
     * 这一栏挡的是「同一 chunk 的光照任务只跑一次」。两者都有可能卡住升格，所以分开读。
     */
    public static long lightChunkClaimOwner(int chunkX, int chunkZ) {
        AtomicLong cell = CHUNK_CLAIMS[LIGHTED].get(ChunkPos.pack(chunkX, chunkZ));
        return cell == null ? 0L : cell.get();
    }

    /** 该 chunk 是否落在某次光照任务的二十五格域内。 */
    public static boolean isLightedClaimed(int chunkX, int chunkZ) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                AtomicLong cell = LIGHT_TASK.get(ChunkPos.pack(chunkX + dx, chunkZ + dz));
                if (cell != null && cell.get() != 0L) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 二十五格里被占的偏移与持有者编号，形如 {@code -1,0=12(deco@984@0,-1) 2,-2=37}；全空时返回
     * {@code none}。
     *
     * <p>只给锁失败的诊断用。{@link #isLightedClaimed} 只回答有没有，分不开「同片区的光照任务持有」
     * 与「另一次装饰持有」，而这两支的处置不同：前者是毫秒级等待，后者要查认领泄漏。
     *
     * <p>持有者后面跟的是 {@link #TAGS} 里的归属，写明它是哪个站点取的、哪一拍、中心在哪；没有括号的
     * 说明该 owner 没有记上归属，两种成因由 {@code tagTrunc} 分开。
     */
    public static String lightDomainOwners(int centerX, int centerZ) {
        StringBuilder sb = new StringBuilder();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                AtomicLong cell = LIGHT_TASK.get(ChunkPos.pack(centerX + dx, centerZ + dz));
                long value = cell == null ? 0L : cell.get();
                if (value == 0L) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(dx).append(',').append(dz).append('=').append(value);
                String tag = TAGS.get(value);
                if (tag != null) {
                    sb.append('(').append(tag).append(')');
                }
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** 停服清空。票随 level 消亡，不逐个撤。 */
    public static void clearWorldState() {
        for (int phase = 1; phase < PHASES; phase++) {
            SECTION_CELLS[phase].clear();
            SECTION_COUNTS[phase].clear();
            CHUNK_CLAIMS[phase].clear();
        }
        LIGHT_TASK.clear();
        TAGS.clear();
    }
}
