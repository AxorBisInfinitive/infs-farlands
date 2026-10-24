package com.inf.farlands.terrain.pipeline;

import java.util.Map;

import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 依赖表的静态声明，加票位到允许档的换算。
 *
 * <p>
 * 版本绑定：vanilla 的 {@code ChunkStatus} 常量名与 {@code ChunkLevel} 的算式取自
 * 26.1.2，升级要重核。
 *
 * <p>
 * 每一行声明四样东西：推到哪一档、依赖哪些档与三轴半径、写域半径、以及欠账判据。欠账判据由
 * 三部分组成，缺一不可：
 *
 * <ul>
 * <li>{@link Row#universe()}，枚举来源。四条判据的来源并不统一，本表逐行复刻各自的来源。</li>
 * <li>{@link Row#pendingMask()}，哪些档算欠账，按位。它按行义写，即「该行推进到的档之下的全部档」，
 * SURFACE 行是 TERRAIN 一位，LIGHTED 行是 UNPROCESSED 到 CARVERS，FULL 行是 UNPROCESSED 到
 * DECORATED。</li>
 * <li>{@link Row#excludeReading()}，是否把正在读回的 section 排除。</li>
 * </ul>
 *
 * <p>
 * 运行时读者只有两处：{@link #allowedStage(int)} 与 {@link #pendingBelowIn}；行声明本身是模型口径的
 * 声明面，调度器接管依赖判据时按它取值。
 */
public final class DependencyTable {

    /** 欠账判据的枚举来源。 */
    public enum Universe {
        /** 无。BIOMES 行没有前置。 */
        NONE,
        /** section 容器的键集，对应 SurfaceFiller 与 CarverFiller 的判据。 */
        CONTAINER,
        /** section 状态表的键集，对应 GenTask 的待点亮判据与装饰的门。 */
        STAGE_MAP,
        /** 窗口并集区间，对应调度器准入里那段竖直判据。 */
        WINDOW_UNION
    }

    /**
     * 一行依赖声明。
     *
     * @param targetStage    本行推进到的档
     * @param requires       依赖的档与三轴半径，每条是 {档, dx, dy, dz}；调度器接管那一步才读
     * @param writeRadius    写域半径，单位是 section，顺序是 x、y、z；调度器接管那一步才读
     * @param universe       欠账判据的枚举来源
     * @param pendingMask    哪些档算欠账，按位，第 i 位是档位 i
     * @param excludeReading 是否把正在读回的 section 排除
     */
    public record Row(int targetStage, int[][] requires, int[] writeRadius, Universe universe,
            int pendingMask, boolean excludeReading) {
    }

    private static int mask(int... stages) {
        int m = 0;
        for (int stage : stages) {
            m |= 1 << stage;
        }
        return m;
    }

    public static final Row BIOMES = new Row(SectionStage.BIOMES, new int[0][], new int[] { 0, 0, 0 },
            Universe.NONE, 0, false);
    public static final Row TERRAIN = new Row(SectionStage.TERRAIN,
            new int[][] { { SectionStage.BIOMES, 0, 0, 0 } }, new int[] { 0, 0, 0 },
            Universe.WINDOW_UNION, mask(SectionStage.UNPROCESSED, SectionStage.BIOMES), false);
    public static final Row SURFACE = new Row(SectionStage.SURFACE,
            new int[][] { { SectionStage.TERRAIN, 0, 0, 0 } }, new int[] { 0, 0, 0 },
            Universe.CONTAINER, mask(SectionStage.TERRAIN), true);
    public static final Row CARVERS = new Row(SectionStage.CARVERS,
            new int[][] { { SectionStage.SURFACE, 0, 0, 0 } }, new int[] { 0, 0, 0 },
            Universe.CONTAINER, mask(SectionStage.SURFACE), true);
    public static final Row DECORATED = new Row(SectionStage.DECORATED,
            new int[][] { { SectionStage.CARVERS, 1, 0, 1 } }, new int[] { 1, 0, 1 },
            Universe.STAGE_MAP, mask(SectionStage.CARVERS), false);
    public static final Row LIGHTED = new Row(SectionStage.LIGHTED,
            new int[][] { { SectionStage.DECORATED, 0, 0, 0 } }, new int[] { 0, 0, 0 },
            Universe.STAGE_MAP,
            mask(SectionStage.UNPROCESSED, SectionStage.BIOMES, SectionStage.TERRAIN, SectionStage.SURFACE,
                    SectionStage.CARVERS),
            false);
    public static final Row FULL = new Row(SectionStage.LIGHTED + 1,
            new int[][] { { SectionStage.LIGHTED, 0, 0, 0 } }, new int[] { 0, 0, 0 },
            Universe.STAGE_MAP, mask(SectionStage.UNPROCESSED, SectionStage.BIOMES, SectionStage.TERRAIN,
                    SectionStage.SURFACE, SectionStage.CARVERS, SectionStage.DECORATED),
            false);

    /** 我们的档到 vanilla 状态的映射，顺序即档位顺序。 */
    private static final ChunkStatus[] STATUS_OF_STAGE = {
            ChunkStatus.EMPTY,
            ChunkStatus.BIOMES,
            ChunkStatus.NOISE,
            ChunkStatus.SURFACE,
            ChunkStatus.CARVERS,
            ChunkStatus.FEATURES,
            ChunkStatus.LIGHT,
    };

    private DependencyTable() {
    }

    /**
     * 该票位级允许推进到的最高档。
     *
     * <p>
     * 取值域是 {@code [-1, 6]}：-1 表示票位已超出 {@code ChunkLevel.MAX_LEVEL}，即该 chunk 未加载，
     * 任何档都不许；0 表示只许停在 {@code UNPROCESSED}，即不许开工。判据是 vanilla 的
     * {@code generationStatus} 与 {@code ChunkStatus.isOrBefore}，不写死 33 与半径。
     */
    public static int allowedStage(int ticketLevel) {
        if (ticketLevel < 0) {
            return -1;
        }
        ChunkStatus allowed = ChunkLevel.generationStatus(ticketLevel);
        if (allowed == null) {
            return -1;
        }
        for (int stage = STATUS_OF_STAGE.length - 1; stage >= 0; stage--) {
            if (STATUS_OF_STAGE[stage].isOrBefore(allowed)) {
                return stage;
            }
        }
        return -1;
    }

    /**
     * 给定段集合里档位低于 {@code limit} 的段数。
     *
     * <p>集合由 {@code Scheduler.sectionsFor} 产出，已按可玩界与在读过滤并升序去重。准入的欠账判据与
     * 第七行的完成判据都走这里，两个判据因此与 {@code GenTask} 的收集读同一份段集合。
     */
    public static int pendingBelowIn(LevelChunk chunk, int[] sections, int limit) {
        int count = 0;
        for (int sy : sections) {
            if (SectionStage.getStage(chunk, sy) < limit) {
                count++;
            }
        }
        return count;
    }

    /**
     * 装饰的写域九格门：每一格都在场、都有已物化的 section、且已有 section 都到 {@code CARVERS}，
     * 在读的 section 除外。不可玩界的邻居只要求在场。
     *
     * <p>判据面取档位表与段容器的交集，两头都不能放宽：
     *
     * <ul>
     * <li>只遍历容器会把构造期的窗口默认段算成 {@code UNPROCESSED}。那些段被物化但从不落档，本来
     * 就不在该被生成的集合里，按容器判会让九格恒假。</li>
     * <li>只遍历档位表会把读回标记留下的残留算进来。那些条目容器里没有，装饰也写不进去，
     * 按整表判会让周围九格的装饰被永久挡死。</li>
     * </ul>
     *
     * <p>交集与写门同宽：{@code DecorationRegion.setBlock} 对未物化的段直接返回假。这条是一条耦合，
     * 若将来写门改成会物化的取法，例如 {@code getSection}，判据面要重新算。
     */
    public static boolean neighborGate(LevelChunk center) {
        if (!(center.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        ChunkPos pos = center.getPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = pos.x() + dx;
                int cz = pos.z() + dz;
                if (!cellReady(cursor.at(cx, cz), cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 九格里某一格是否可过：在场、可玩界、可写集合非空、且其中没有低于 {@code CARVERS} 的段。 */
    private static boolean cellReady(LevelChunk neighbor, int cx, int cz) {
        if (neighbor == null) {
            return false;
        }
        if (!WorldBounds.inChunkRange(cx, cz)) {
            return true;
        }
        int[] count = { 0 };
        boolean[] below = { false };
        Map<Integer, LevelChunkSection> container = ((WindowedChunk) neighbor).windowedAllSections();
        SectionStage.forEachStage(neighbor, (sy, stage) -> {
            if (sy == null || !container.containsKey(sy)) {
                return;
            }
            count[0]++;
            if (stage < SectionStage.CARVERS && !SectionIO.isReading(neighbor, sy)) {
                below[0] = true;
            }
        });
        return count[0] > 0 && !below[0];
    }
}
