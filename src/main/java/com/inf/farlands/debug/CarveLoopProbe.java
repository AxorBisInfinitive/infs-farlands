package com.inf.farlands.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.hash.HashMath;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 诊断：热环的三条读数。临时仪器，取到读数后与同批探针一并删除。
 *
 * <p>三条各自回答一个问题，合起来分开三种机制：作业拿到的段档位是怎么样的、档位写有没有被丢、
 * 档位有没有被读回覆盖。分头限流：任务行按 chunk 每秒最多一条，另两条按「位置加段号」去重。
 *
 * <p>全部挂在 {@code logScheduler} 上，关掉即零输出。前缀与既有探针族一致，FLLOOP 归本类。
 */
public final class CarveLoopProbe {

    /** 同一 chunk 的任务行最小间隔，单位毫秒。 */
    private static final long TASK_INTERVAL_MILLIS = 1000L;

    /** 阶段名，下标与 {@link SectionStage} 的常量同序。 */
    private static final String[] STAGE_NAMES = {
            "UNPROCESSED", "BIOMES", "TERRAIN", "SURFACE", "CARVERS", "DECORATED", "LIGHTED"
    };

    /** chunk 键到上次打任务行的墙钟毫秒。 */
    private static final Map<Long, Long> TASK_LAST = new ConcurrentHashMap<>();

    /** 空转作业的同一份限流，与上一张表分开。 */
    private static final Map<Long, Long> NOOP_LAST = new ConcurrentHashMap<>();

    /** 已打过的丢弃项与覆盖项，键是位置加段号的哈希。 */
    private static final Set<Long> DROP_LOGGED = ConcurrentHashMap.newKeySet();

    private static final Set<Long> RESTORE_LOGGED = ConcurrentHashMap.newKeySet();

    /** 已打过的移除项与整条清空项。 */
    private static final Set<Long> REMOVED_LOGGED = ConcurrentHashMap.newKeySet();

    private static final Set<Long> CLEARED_LOGGED = ConcurrentHashMap.newKeySet();

    /** 每格的物化与移除计数。两者并排才看得出「移除之后又被重建」这条环。 */
    private static final Map<Long, Cell> CELLS = new ConcurrentHashMap<>();

    /** 物化事件总数，汇总行的节奏与有效性证据都用它。 */
    private static final AtomicLong MATERIALIZE_EVENTS = new AtomicLong();

    /** 汇总间隔，按物化事件数。 */
    private static final long MATERIALIZE_SUMMARY_EVERY = 20000L;

    /** 汇总行最多列几格。 */
    private static final int MATERIALIZE_TOP = 8;

    private CarveLoopProbe() {
    }

    /** 一格的计数。物化在主线程与池线程都会写，故用原子计数。 */
    private static final class Cell {

        private final ChunkPos pos;

        private final AtomicInteger materialized = new AtomicInteger();

        private final AtomicInteger removed = new AtomicInteger();

        private Cell(ChunkPos pos) {
            this.pos = pos;
        }
    }

    /**
     * 一段被物化。按 chunk 累计，每 {@link #MATERIALIZE_SUMMARY_EVERY} 次打一行前若干格，把物化数与
     * 移除数并排给出。
     *
     * <p>参数可以为 null：调用方在 {@code ImposterProtoChunk} 的构造期解包 {@code getWrapped()}，那时
     * 包装字段还没有赋值。这一层只是读数，不能因此把区块读盘的异常抛出去。
     */
    public static void materialize(LevelChunk chunk, int sectionY) {
        if (!FarlandsConfig.logScheduler || chunk == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        CELLS.computeIfAbsent(pos.pack(), k -> new Cell(pos)).materialized.incrementAndGet();
        long events = MATERIALIZE_EVENTS.incrementAndGet();
        if (events % MATERIALIZE_SUMMARY_EVERY == 0L) {
            summary();
        }
    }

    /** 物化数最多的若干格，各自带上移除数。 */
    private static void summary() {
        List<Cell> cells = new ArrayList<>(CELLS.values());
        cells.sort((a, b) -> Integer.compare(b.materialized.get(), a.materialized.get()));
        StringBuilder sb = new StringBuilder();
        int listed = Math.min(MATERIALIZE_TOP, cells.size());
        for (int i = 0; i < listed; i++) {
            Cell cell = cells.get(i);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(cell.pos.x()).append(',').append(cell.pos.z())
                    .append(":mat=").append(cell.materialized.get())
                    .append(",rm=").append(cell.removed.get());
        }
        InfsFarlands.LOGGER.info("FLMAT events={} chunks={} top={}",
                MATERIALIZE_EVENTS.get(), cells.size(), sb);
    }

    /** 档位名的可读形式。 */
    public static String stageName(int stage) {
        return stage >= 0 && stage < STAGE_NAMES.length ? STAGE_NAMES[stage] : "?" + stage;
    }

    /**
     * 任务行是否该打：同一 chunk 每秒最多一条，空转与非空转各一条。
     *
     * <p>两条通道分开计数，是因为空转作业占了绝大多数，共用一个通道时它们会把非空转那条挤掉，
     * 而判读要看的是同一格在同一秒里两种形态各是什么样。
     */
    public static boolean shouldLogTask(long posKey, boolean noop) {
        if (!FarlandsConfig.logScheduler) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<Long, Long> last = noop ? NOOP_LAST : TASK_LAST;
        Long previous = last.get(posKey);
        if (previous != null && now - previous < TASK_INTERVAL_MILLIS) {
            return false;
        }
        last.put(posKey, now);
        return true;
    }

    /** 档位写入被丢：身份为 0 或已注销。按位置加段号去重。 */
    public static void drop(LevelChunk chunk, int sectionY, int wanted) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        if (!DROP_LOGGED.add(HashMath.hash(pos.x(), sectionY, pos.z()))) {
            return;
        }
        InfsFarlands.LOGGER.info("FLSTG drop pos={},{} sy={} wanted={}",
                pos.x(), pos.z(), sectionY, stageName(wanted));
    }

    /** 档位被读回覆盖：目标档早于内存里的当前档。按位置加段号去重。 */
    public static void restore(LevelChunk chunk, int sectionY, int target) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        int current = SectionStage.getStage(chunk, sectionY);
        if (target >= current) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        if (!RESTORE_LOGGED.add(HashMath.hash(pos.x(), sectionY, pos.z()))) {
            return;
        }
        InfsFarlands.LOGGER.info("FLRB restore pos={},{} sy={} from={} to={}",
                pos.x(), pos.z(), sectionY, stageName(current), stageName(target));
    }

    /** 段状态被移除：清理释放段。按位置加段号去重，但计数每次都要记。 */
    public static void removeSection(LevelChunk chunk, int sectionY, int stage) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        Cell cell = CELLS.get(pos.pack());
        if (cell != null) {
            cell.removed.incrementAndGet();
        }
        if (!REMOVED_LOGGED.add(HashMath.hash(pos.x(), sectionY, pos.z()))) {
            return;
        }
        InfsFarlands.LOGGER.info("FLRMV remove pos={},{} sy={} stage={}",
                pos.x(), pos.z(), sectionY, stageName(stage));
    }

    /** 整条段状态被清掉：卸载。按位置去重。 */
    public static void clearChunk(LevelChunk chunk, long counterId, int entries) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        if (!CLEARED_LOGGED.add(pos.pack())) {
            return;
        }
        InfsFarlands.LOGGER.info("FLCLR clear pos={},{} ctr={} entries={}",
                pos.x(), pos.z(), counterId, entries);
    }
}
