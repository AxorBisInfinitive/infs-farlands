package com.inf.farlands.terrain.pipeline;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.inf.farlands.util.map.Long2ObjectStripedMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 六相的所有权表：认领、放域、查询，票与所有权成对。
 *
 * <p>相与粒度：BIOMES、TERRAIN、SURFACE 段级；CARVERS、DECORATED、LIGHTED chunk 级。TERRAIN 另持
 * 该 chunk 的高度图写权，所以它同时占段与 chunk 两条。LIGHTED 另有一个二十五格的域锁，与装饰的九格
 * 豁免是同一张表的两相。
 *
 * <p>键是「维度加坐标」。本类是进程级静态表，而 chunk 坐标在两个维度里是同一个数：不分维度时主世界
 * 与下界的同坐标任务会互相挡住，一次认领失败还会把另一维度的候选带进去重分支。旧版靠「每个 level 一个
 * 实例」天然隔离，本类没有那层实例，所以在表的外层加维度桶，内层的键与算法都不变。
 *
 * <p>纪律：按键排序逐个取，任一失败回滚全部已取项并返回 0；每格记持有者编号，放域必须带回编号，
 * 只清属于自己的格。主线程单点认领不会死锁；排序与回滚保留，因为多个 chunk 的域会相交，失败必须干净。
 *
 * <p>线程：认领只在主线程，释放可来自 farlands-gen 与 farlands-light，所以外层是 ConcurrentHashMap，
 * 桶一经发布不再换字段。放域按编号 CAS，传错维度不会误放别人持有的格，但会漏放成永久占用，所以
 * unlockCells 必须用取域时的同一维度。
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

    /** 取域编号，全局递增；0 表示空闲。跨维度共用一个序列，放域才能只凭编号判归属。 */
    private static final AtomicLong OWNER_SEQ = new AtomicLong();

    /** 维度到它自己的一套表。键一律取自 level.dimension()，不自造同值实例。 */
    private static final ConcurrentHashMap<ResourceKey<Level>, Tables> BY_DIMENSION = new ConcurrentHashMap<>();

    private OwnershipTable() {
    }

    /**
     * 该维度的桶，没有就建。只给写入方用。
     *
     * <p>读方走 {@link #liveTables}：给一个从未有过认领的维度建桶，会白付 LIGHT_TASK 那一张表的
     * 固定成本。
     */
    private static Tables tables(ResourceKey<Level> dimension) {
        Tables tables = BY_DIMENSION.get(dimension);
        if (tables != null) {
            return tables;
        }
        Tables created = new Tables();
        Tables raced = BY_DIMENSION.putIfAbsent(dimension, created);
        return raced != null ? raced : created;
    }

    /** 该维度的桶，没有则 null。只给读与放用。 */
    private static Tables liveTables(ResourceKey<Level> dimension) {
        return BY_DIMENSION.get(dimension);
    }

    /**
     * 一个维度的六相表。
     *
     * <p>三组表按相惰性建：Long2ObjectStripedMap 固定 256 段，每张表无论容量提示多小都要付约
     * 106KB，按维度全建会让内存随维度数线性涨。段级两张在当前树上没有写者，因此永远不会被建，
     * 读方必须按 null 处理。
     *
     * <p>字段一经发布不再替换；惰性建表用 CAS 发布，读者拿到的要么是 null 要么是完整建好的表。
     */
    private static final class Tables {

        /** 段级相：chunkKey 映射到段号到持有者编号。 */
        private final AtomicReferenceArray<Long2ObjectStripedMap<Long2ObjectStripedMap<AtomicLong>>> sectionCells =
                new AtomicReferenceArray<>(PHASES);

        /** 段级相的占用计数：chunkKey 到该相已占段数，供 O(1) 判「该 chunk 有没有生成在途」。 */
        private final AtomicReferenceArray<Long2ObjectStripedMap<AtomicLong>> sectionCounts =
                new AtomicReferenceArray<>(PHASES);

        /** chunk 级相：chunkKey 到持有者编号。 */
        private final AtomicReferenceArray<Long2ObjectStripedMap<AtomicLong>> chunkClaims =
                new AtomicReferenceArray<>(PHASES);

        /** 光照任务与装饰域的二十五格锁，键是 chunkKey。每维一张，便宜到不必惰性。 */
        private final Long2ObjectStripedMap<AtomicLong> lightTask = new Long2ObjectStripedMap<>(1 << 8);

        Long2ObjectStripedMap<Long2ObjectStripedMap<AtomicLong>> sectionCells(int phase) {
            return lazy(this.sectionCells, phase);
        }

        Long2ObjectStripedMap<Long2ObjectStripedMap<AtomicLong>> sectionCellsOrNull(int phase) {
            return this.sectionCells.get(phase);
        }

        Long2ObjectStripedMap<AtomicLong> sectionCounts(int phase) {
            return lazy(this.sectionCounts, phase);
        }

        Long2ObjectStripedMap<AtomicLong> sectionCountsOrNull(int phase) {
            return this.sectionCounts.get(phase);
        }

        Long2ObjectStripedMap<AtomicLong> chunkClaims(int phase) {
            return lazy(this.chunkClaims, phase);
        }

        Long2ObjectStripedMap<AtomicLong> chunkClaimsOrNull(int phase) {
            return this.chunkClaims.get(phase);
        }

        Long2ObjectStripedMap<AtomicLong> lightTask() {
            return this.lightTask;
        }
    }

    /** 取该相的表，没有就建一张发布出去。 */
    private static <V> Long2ObjectStripedMap<V> lazy(AtomicReferenceArray<Long2ObjectStripedMap<V>> slots, int phase) {
        Long2ObjectStripedMap<V> table = slots.get(phase);
        if (table != null) {
            return table;
        }
        Long2ObjectStripedMap<V> created = new Long2ObjectStripedMap<>(1 << 12);
        // CAS 失败说明别的线程刚发布了一张；两张表内容不同，用输的那张会丢认领。
        return slots.compareAndSet(phase, null, created) ? created : slots.get(phase);
    }

    /** 段级认领：取该段。成功返回持有者编号，失败返回 0。 */
    public static long tryClaimSection(ResourceKey<Level> dimension, int phase, long chunkKey, int sectionY) {
        Tables tables = tables(dimension);
        Long2ObjectStripedMap<Long2ObjectStripedMap<AtomicLong>> cells = tables.sectionCells(phase);
        Long2ObjectStripedMap<AtomicLong> perChunk = cells.computeIfAbsent(chunkKey,
                k -> new Long2ObjectStripedMap<>(1 << 6));
        AtomicLong cell = perChunk.computeIfAbsent(sectionY, k -> new AtomicLong());
        long owner = OWNER_SEQ.incrementAndGet();
        if (!cell.compareAndSet(0L, owner)) {
            return 0L;
        }
        tables.sectionCounts(phase).computeIfAbsent(chunkKey, k -> new AtomicLong()).incrementAndGet();
        return owner;
    }

    /** 段级放域：只清带回来的那个编号。 */
    public static void releaseSection(ResourceKey<Level> dimension, int phase, long chunkKey, int sectionY,
            long owner) {
        if (owner == 0L) {
            return;
        }
        Tables tables = liveTables(dimension);
        if (tables == null) {
            return;
        }
        Long2ObjectStripedMap<Long2ObjectStripedMap<AtomicLong>> cells = tables.sectionCellsOrNull(phase);
        if (cells == null) {
            return;
        }
        Long2ObjectStripedMap<AtomicLong> perChunk = cells.get(chunkKey);
        if (perChunk == null) {
            return;
        }
        AtomicLong cell = perChunk.get(sectionY);
        if (cell != null && cell.compareAndSet(owner, 0L)) {
            Long2ObjectStripedMap<AtomicLong> counts = tables.sectionCountsOrNull(phase);
            if (counts != null) {
                AtomicLong count = counts.get(chunkKey);
                if (count != null) {
                    count.decrementAndGet();
                }
            }
        }
    }

    /** 段级查询：该段有没有人占。表没建过即无人占。 */
    public static boolean isSectionClaimed(ResourceKey<Level> dimension, int phase, long chunkKey, int sectionY) {
        Tables tables = liveTables(dimension);
        if (tables == null) {
            return false;
        }
        Long2ObjectStripedMap<Long2ObjectStripedMap<AtomicLong>> cells = tables.sectionCellsOrNull(phase);
        if (cells == null) {
            return false;
        }
        Long2ObjectStripedMap<AtomicLong> perChunk = cells.get(chunkKey);
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
        AtomicLong cell = tables(level.dimension()).chunkClaims(phase)
                .computeIfAbsent(pos.pack(), k -> new AtomicLong());
        long owner = OWNER_SEQ.incrementAndGet();
        if (!cell.compareAndSet(0L, owner)) {
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
        Tables tables = liveTables(level.dimension());
        if (tables == null) {
            return;
        }
        Long2ObjectStripedMap<AtomicLong> claims = tables.chunkClaimsOrNull(phase);
        if (claims == null) {
            return;
        }
        AtomicLong cell = claims.get(pos.pack());
        if (cell != null && cell.compareAndSet(owner, 0L)) {
            GenQueue.removeGenTicket(level, pos);
        }
    }

    /** chunk 级查询：该相有没有人占该 chunk。表没建过即无人占。 */
    public static boolean isChunkClaimed(ResourceKey<Level> dimension, int phase, long chunkKey) {
        Tables tables = liveTables(dimension);
        if (tables == null) {
            return false;
        }
        Long2ObjectStripedMap<AtomicLong> claims = tables.chunkClaimsOrNull(phase);
        if (claims == null) {
            return false;
        }
        AtomicLong cell = claims.get(chunkKey);
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
        ResourceKey<Level> dimension = level.dimension();
        Long2ObjectStripedMap<AtomicLong> lightTask = tables(dimension).lightTask();
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
            // 同维度的装饰正在写这一片：让路，回滚已取项。别的维度同坐标不算，那里的段是另一批对象。
            if (isChunkClaimed(dimension, DECORATED, keys[i])) {
                rollbackCells(lightTask, taken, acquired, owner);
                return 0L;
            }
            AtomicLong cell = lightTask.computeIfAbsent(keys[i], k -> new AtomicLong());
            if (cell.compareAndSet(0L, owner)) {
                taken[acquired++] = keys[i];
            } else {
                rollbackCells(lightTask, taken, acquired, owner);
                return 0L;
            }
        }
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
    private static void rollbackCells(Long2ObjectStripedMap<AtomicLong> lightTask, long[] taken, int acquired,
            long owner) {
        for (int j = 0; j < acquired; j++) {
            AtomicLong held = lightTask.get(taken[j]);
            if (held != null) {
                held.compareAndSet(owner, 0L);
            }
        }
    }

    /**
     * 放域：清中心二十五格中属于该编号的格。
     *
     * <p>维度必须与取域时同一个：编号 CAS 保证不会误放别人持有的格，但维度传错就够不到自己那几格，
     * 它们会永久占用。
     */
    public static void unlockCells(ResourceKey<Level> dimension, int centerX, int centerZ, long owner) {
        if (owner == 0L) {
            return;
        }
        Tables tables = liveTables(dimension);
        if (tables == null) {
            return;
        }
        Long2ObjectStripedMap<AtomicLong> lightTask = tables.lightTask();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                AtomicLong cell = lightTask.get(ChunkPos.pack(centerX + dx, centerZ + dz));
                if (cell != null) {
                    cell.compareAndSet(owner, 0L);
                }
            }
        }
    }

    /** 该 chunk 是否有生成侧的在途写者：前三相任一有段被占，或 TERRAIN 的 chunk 级写权被占。 */
    public static boolean anyGenClaim(ResourceKey<Level> dimension, long chunkKey) {
        Tables tables = liveTables(dimension);
        if (tables == null) {
            return false;
        }
        if (isChunkClaimed(dimension, TERRAIN, chunkKey)) {
            return true;
        }
        for (int phase = BIOMES; phase <= SURFACE; phase++) {
            // 段级表在这棵树里没有写者，因此恒未建；先判空，不要在这种读路径上物化它。
            Long2ObjectStripedMap<AtomicLong> counts = tables.sectionCountsOrNull(phase);
            if (counts == null) {
                continue;
            }
            AtomicLong count = counts.get(chunkKey);
            if (count != null && count.get() > 0L) {
                return true;
            }
        }
        return false;
    }

    /** 该 chunk 是否被某次装饰的写域覆盖。 */
    public static boolean isDecoratedClaimed(ResourceKey<Level> dimension, long chunkKey) {
        return isChunkClaimed(dimension, DECORATED, chunkKey);
    }

    /** 该 chunk 是否落在某次光照任务的二十五格域内。 */
    public static boolean isLightedClaimed(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        Tables tables = liveTables(dimension);
        if (tables == null) {
            return false;
        }
        Long2ObjectStripedMap<AtomicLong> lightTask = tables.lightTask();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                AtomicLong cell = lightTask.get(ChunkPos.pack(chunkX + dx, chunkZ + dz));
                if (cell != null && cell.get() != 0L) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 停服清空。票随 level 消亡，不逐个撤。
     *
     * <p>整桶丢弃：池上在途任务可能晚一步醒来并写进已摘下的桶，那些写落在没人再读的对象上，
     * 与「清完即丢弃」等价。
     */
    public static void clearWorldState() {
        BY_DIMENSION.clear();
    }
}
