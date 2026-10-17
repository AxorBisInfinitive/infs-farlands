package com.inf.farlands.terrain.pipeline;

import java.util.function.IntConsumer;

import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.PlayerSnapshot;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 依赖表的静态声明，加票位到允许档的换算。本类不驱动任何作业，只被自检读。
 *
 * <p>版本绑定：vanilla 的 {@code ChunkStatus} 常量名与 {@code ChunkLevel} 的算式取自 26.1.2，升级要重核。
 *
 * <p>每一行声明四样东西：推到哪一档、依赖哪些档与三轴半径、写域半径、以及欠账判据。欠账判据由
 * 三部分组成，缺一不可：
 *
 * <ul>
 * <li>{@link Row#universe()}，枚举来源。今天四条判据的来源并不统一，本表逐行复刻各自的来源，
 * 不统一：统一成「section 容器与档位表的并集」是行为变化，留给调度器接管的下一步。</li>
 * <li>{@link Row#pendingMask()}，哪些档算欠账，按位。它按行义写，即「该行推进到的档之下的全部档」，
 * SURFACE 行是 TERRAIN 一位，LIGHTED 行是 UNPROCESSED 到 CARVERS，FULL 行是 UNPROCESSED 到
 * DECORATED。不按光照会升的那一档集合写：那个集合是升格判据的匹配面，与「欠账档位」不是同一个
 * 概念，跟着它写会让本行认为停在 TERRAIN 的段不算欠账。</li>
 * <li>{@link Row#excludeReading()}，是否把正在读回的 section 排除。今天四条判据里三条排，装饰
 * 那条按档位表整表扫。</li>
 * </ul>
 *
 * <p>{@link Row#requires()} 与 {@link Row#writeRadius()} 是给调度器接管那一步用的声明，本节无调用点。
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
        /** 窗口并集区间，对应 GenQueue 的 hasUnprocessed 与 hasWorkInWindow。 */
        WINDOW_UNION
    }

    /**
     * 一行依赖声明。
     *
     * @param targetStage  本行推进到的档
     * @param requires     依赖的档与三轴半径，每条是 {档, dx, dy, dz}；调度器接管那一步才读
     * @param writeRadius  写域半径，单位是 section，顺序是 x、y、z；调度器接管那一步才读
     * @param universe     欠账判据的枚举来源
     * @param pendingMask  哪些档算欠账，按位，第 i 位是档位 i
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
     * <p>取值域是 {@code [-1, 6]}：-1 表示票位已超出 {@code ChunkLevel.MAX_LEVEL}，即该 chunk 未加载，
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

    /** FULL 是否允许，即允许状态就是 FULL。判据同 {@link #allowedStage(int)}。 */
    public static boolean fullAllowed(int ticketLevel) {
        return ticketLevel >= 0 && ChunkLevel.generationStatus(ticketLevel) == ChunkStatus.FULL;
    }

    /**
     * 票位分带，供读数对照那个三带推断：0 是允许 FULL，1 是允许状态逐档下降，2 是未加载。
     */
    public static int bandOf(int ticketLevel) {
        if (ticketLevel < 0 || ChunkLevel.generationStatus(ticketLevel) == null) {
            return 2;
        }
        return ChunkLevel.generationStatus(ticketLevel) == ChunkStatus.FULL ? 0 : 1;
    }

    /**
     * 该 chunk 到该维度最近玩家的 Chebyshev 距离，读每 tick 的玩家快照；无玩家时返回 0。
     * 排序键复用本方法，不许另写一份。
     */
    public static int nearestPlayerDistance(ServerLevel level, int cx, int cz) {
        return PlayerSnapshot.nearestDistance(level.dimension(), cx, cz);
    }

    /** 该维度的有效视距，读玩家快照；无玩家时回退 8。 */
    public static int viewDistance(ServerLevel level) {
        return PlayerSnapshot.viewDistance(level.dimension());
    }

    /**
     * 该行的欠账 section 个数。计数只为读数，不改任何判据。
     *
     * <p>过滤顺序与今天的判据一致：可玩界、档位落在 mask 内、按需排除在读。档位一律现读，不
     * 复用枚举回调给的 stage，这样三条来源共用同一条过滤路径。
     */
    public static int pendingCount(LevelChunk chunk, Row row) {
        if (row.pendingMask() == 0) {
            return 0;
        }
        int[] count = { 0 };
        long chunkKey = chunk.getPos().pack();
        IntConsumer consider = sy -> {
            if (!WorldBounds.inSection(sy)) {
                return;
            }
            if ((row.pendingMask() & (1 << SectionStage.getStage(chunk, sy))) == 0) {
                return;
            }
            if (row.excludeReading() && SectionIO.isReading(chunkKey, sy)) {
                return;
            }
            count[0]++;
        };
        switch (row.universe()) {
            case CONTAINER -> {
                for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
                    if (sy != null) {
                        consider.accept(sy);
                    }
                }
            }
            case STAGE_MAP -> SectionStage.forEachStage(chunk, (sy, stage) -> consider.accept(sy));
            case WINDOW_UNION -> EntitySectionWindow.forEachSectionInAnyWindow(
                    chunk.getLevel().dimension(), consider);
            case NONE -> {
            }
        }
        return count[0];
    }

    /**
     * 装饰的写域九格门，表侧的求值：每一格都在场、都有已物化的 section、且已有 section 都到
     * {@code CARVERS}，在读的 section 除外。不可玩界的邻居只要求在场，与今天那句例外同源。
     *
     * <p>这里与 {@code DecorationFiller.neighborhood} 是同一语义的两份表达式，所以自检把两侧都算出来
     * 比对，不靠单侧。
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
                LevelChunk neighbor = cursor.at(cx, cz);
                if (neighbor == null) {
                    return false;
                }
                if (!WorldBounds.inChunkRange(cx, cz)) {
                    continue;
                }
                long neighborKey = neighbor.getPos().pack();
                boolean[] any = { false };
                boolean[] belowCarvers = { false };
                SectionStage.forEachStage(neighbor, (sy, stage) -> {
                    any[0] = true;
                    if (stage < SectionStage.CARVERS && !SectionIO.isReading(neighborKey, sy)) {
                        belowCarvers[0] = true;
                    }
                });
                if (!any[0] || belowCarvers[0]) {
                    return false;
                }
            }
        }
        return true;
    }
}
