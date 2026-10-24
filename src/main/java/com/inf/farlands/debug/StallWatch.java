package com.inf.farlands.debug;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.pipeline.DependencyTable;
import com.inf.farlands.terrain.pipeline.InViewSweep;
import com.inf.farlands.terrain.pipeline.OwnershipTable;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.util.window.EntitySectionWindow;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 诊断：空闲面读数。
 *
 * <p>计数由 {@link InViewSweep} 那一窗给出，本类不再自己扫一遍；它只负责判定、限流与取样。这样同一套
 * 判据只有一份，而读数说的是「补提交之后还剩多少」。
 *
 * <p>四种形态靠四个数分开：视距内扫过多少格、其中没有壳的、档位表为空或有段低于 LIGHTED 的、以及
 * 有欠账却不在候选表里的。最后一项按票位分三带，票位不大于 FULL 档的属提交侧，大于它的属准入侧。
 *
 * <p>停摆的判据是余量连续多窗逐字不变：正常推进时它每窗都在动，停摆时它连续多窗不动。判成的那一窗
 * 另取最近、中位、最远三处打细节，并各自给出九格普查。每维最多八行明细，其余只进汇总，而汇总行带上
 * 本窗全部计数。只在 {@code logScheduler} 打开时工作。定位完成后与同批探针一并删除。
 */
public final class StallWatch {

    /** 连续多少个窗余量不变才算停摆。 */
    private static final int STALL_STREAK = 3;

    /** 每维最多打多少行停摆明细。 */
    private static final int LOG_LIMIT = 8;

    /** 停摆持续时，每隔多少个窗再补一行。 */
    private static final int REPEAT_EVERY = 20;

    /** 汇总行的间隔：每窗一行，短会话也拿得到读数。 */
    private static final int SUMMARY_EVERY = 1;

    /** 未处理段号最多列几个。 */
    private static final int UNPROC_LIMIT = 4;

    /** 每维的状态。 */
    private static final class State {

        private int windows;
        private int stalled;
        private int emitted;
        /** 上一窗的余量，初值取 -1 以便与任何真实值都不相等。 */
        private int lastStuck = -1;
        /** 余量连续保持同一个值已经多少窗。 */
        private int unchanged;
        private int lastRadius;
        private int lastScanned;
        private int lastNoShell;
        private int lastNoStage;
        private int lastBelowLighted;
        private int lastStuckTotal;
        private int lastStuckFull;
        private int lastStuckAbove;
        private int lastStuckNoHolder;
        private int lastCandInView;
        private int lastSubmitted;
    }

    private static final Map<ResourceKey<Level>, State> STATES = new ConcurrentHashMap<>();

    private StallWatch() {
    }

    /** 每 200 tick 一次，由 {@code FarlandsTick} 的周期块在 {@code InViewSweep} 之后调用。 */
    public static void flush(MinecraftServer server) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            InViewSweep.Stats stats = InViewSweep.last(level.dimension());
            if (stats == null || stats.scanned() == 0) {
                continue;
            }
            report(level, level.dimension(), stats);
        }
    }

    /** 判定、限流与取样。计数全部来自本窗的 {@link InViewSweep.Stats}。 */
    private static void report(ServerLevel level, ResourceKey<Level> dimension, InViewSweep.Stats stats) {
        int radius = stats.radius();
        int scanned = stats.scanned();
        int noShell = stats.noShell();
        int noStage = stats.noStage();
        int belowLighted = stats.belowLighted();
        int stuck = stats.stuck();
        int candInView = stats.candInView();
        int submitted = stats.submitted();
        int stuckFull = stats.stuckFull();
        int stuckAbove = stats.stuckAbove();
        int stuckNoHolder = stats.stuckNoHolder();
        State state = STATES.computeIfAbsent(dimension, key -> new State());
        state.windows++;
        state.lastRadius = radius;
        state.lastScanned = scanned;
        state.lastNoShell = noShell;
        state.lastNoStage = noStage;
        state.lastBelowLighted = belowLighted;
        state.lastStuckTotal = stuck;
        state.lastStuckFull = stuckFull;
        state.lastStuckAbove = stuckAbove;
        state.lastStuckNoHolder = stuckNoHolder;
        state.lastCandInView = candInView;
        state.lastSubmitted = submitted;
        // 停摆的判据是「余量逐字不变」：正常推进时它每窗都在动，而停摆时它连续多窗不动。
        if (stuck == state.lastStuck) {
            state.unchanged++;
        } else {
            state.lastStuck = stuck;
            state.unchanged = 1;
        }
        boolean stalled = stuck > 0 && state.unchanged >= STALL_STREAK;
        if (stalled) {
            state.stalled++;
        }
        boolean emit = stalled && state.emitted < LOG_LIMIT
                && (state.unchanged == STALL_STREAK
                        || (state.unchanged - STALL_STREAK) % REPEAT_EVERY == 0);
        if (emit) {
            state.emitted++;
            InfsFarlands.LOGGER.info(
                    "FLSTALL dim={} radius={} scanned={} noShell={} noStage={} belowL={} stuck={} "
                            + "stuckFull={} stuckAbove={} stuckNoHolder={} candInView={} submitted={} "
                            + "unchanged={} rings={}",
                    dimension.identifier(), radius, scanned, noShell, noStage, belowLighted, stuck,
                    stuckFull, stuckAbove, stuckNoHolder, candInView, submitted,
                    state.unchanged, stats.rings());
            logSamples(level, dimension, stats.ringRep(), stats.rings(), stuck);
        }
        if (state.windows % SUMMARY_EVERY == 0) {
            InfsFarlands.LOGGER.info(
                    "FLSTALL summary dim={} windows={} stalled={} lines={} last(radius={},scanned={},"
                            + "noShell={},noStage={},belowL={},stuck={},stuckFull={},stuckAbove={},"
                            + "stuckNoHolder={},candInView={},submitted={})",
                    dimension.identifier(), state.windows, state.stalled, state.emitted,
                    state.lastRadius, state.lastScanned, state.lastNoShell, state.lastNoStage,
                    state.lastBelowLighted, state.lastStuckTotal, state.lastStuckFull,
                    state.lastStuckAbove, state.lastStuckNoHolder, state.lastCandInView,
                    state.lastSubmitted);
        }
    }

    /**
     * 取最近、中位、最远各一处打细节。
     *
     * <p>三处都取最近的会让三个样本落在同一环上，只能说明那一环的样子；按距离分开取，才看得出这是
     * 全片同形的一层，还是只有某一圈这样。
     */
    private static void logSamples(ServerLevel level, ResourceKey<Level> dimension, int[][] ringRep,
            Int2IntOpenHashMap rings, int stuck) {
        if (stuck == 0) {
            return;
        }
        int nearest = -1;
        int farthest = -1;
        int cumulative = 0;
        int median = -1;
        int half = (stuck + 1) / 2;
        for (int distance = 0; distance < ringRep.length; distance++) {
            int count = rings.get(distance);
            if (count <= 0) {
                continue;
            }
            if (nearest < 0) {
                nearest = distance;
            }
            farthest = distance;
            if (median < 0) {
                cumulative += count;
                if (cumulative >= half) {
                    median = distance;
                }
            }
        }
        int[] picked = { nearest, median, farthest };
        int last = -1;
        for (int distance : picked) {
            if (distance < 0 || distance == last) {
                continue;
            }
            last = distance;
            int[] rep = ringRep[distance];
            if (rep != null) {
                logSample(level, dimension, distance, rep[0], rep[1]);
                logCells(level, dimension, rep[0], rep[1]);
            }
        }
    }

    /**
     * 九格普查：样本周围有哪些格会挡住装饰门。
     *
     * <p>判据是格子在场与否、档位表条目数、以及最低档。只列最低档低于 CARVERS 的格，最多四格；在场
     * 但最低档已到 CARVERS 的格不列，空表列成 {@code e=0}。
     *
     * <p>本方法只作描述，不重算门成不成立：门的判据只有 {@code DependencyTable} 那一份，这里给的是
     * 每一格的形态，供判断挡住门的是「空表」还是「只留了未处理记录」。
     */
    private static void logCells(ServerLevel level, ResourceKey<Level> dimension, int cx, int cz) {
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        StringBuilder text = new StringBuilder();
        int listed = 0;
        for (int dx = -1; dx <= 1 && listed < 4; dx++) {
            for (int dz = -1; dz <= 1 && listed < 4; dz++) {
                LevelChunk neighbour = cursor.at(cx + dx, cz + dz);
                if (neighbour == null) {
                    text.append('(').append(dx).append(',').append(dz).append(")absent ");
                    listed++;
                    continue;
                }
                int[] stats = { 0, Integer.MAX_VALUE };
                SectionStage.forEachStage(neighbour, (sy, stage) -> {
                    stats[0]++;
                    if (stage < stats[1]) {
                        stats[1] = stage;
                    }
                });
                if (stats[0] > 0 && stats[1] >= SectionStage.CARVERS) {
                    continue;
                }
                text.append('(').append(dx).append(',').append(dz).append(")e=").append(stats[0])
                        .append("min=").append(stats[0] == 0 ? "-" : String.valueOf(stats[1]))
                        .append(' ');
                listed++;
            }
        }
        if (listed == 0) {
            return;
        }
        InfsFarlands.LOGGER.info("FLSTALL cells dim={} chunk={},{} [{}]",
                dimension.identifier(), cx, cz, text.toString().trim());
    }

    /** 一个欠账格的细节：段表直方图、读回闩、三张认领表、九格门、未处理段在不在段集合里。 */
    private static void logSample(ServerLevel level, ResourceKey<Level> dimension, int distance, int cx, int cz) {
        ChunkPos pos = new ChunkPos(cx, cz);
        LevelChunk chunk = ChunkReadiness.chunkAt(level, pos);
        if (chunk == null) {
            InfsFarlands.LOGGER.info("FLSTALL sample dim={} chunk={},{} dist={} chunk=none",
                    dimension.identifier(), cx, cz, distance);
            return;
        }
        int[] sections = Scheduler.sectionsFor(chunk);
        int[] histogram = new int[SectionStage.LIGHTED + 1];
        int[] unprocessed = new int[UNPROC_LIMIT];
        // 0 是未处理段总数，1 是已收进列表的个数，2 是其中落在段集合里的个数。
        int[] counts = new int[3];
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage >= 0 && stage < histogram.length) {
                histogram[stage]++;
            }
            if (stage == SectionStage.UNPROCESSED) {
                counts[0]++;
                for (int candidateSy : sections) {
                    if (candidateSy == sy) {
                        counts[2]++;
                        break;
                    }
                }
                if (counts[1] < unprocessed.length) {
                    unprocessed[counts[1]++] = sy;
                }
            }
        });
        // 九格门的第一处不成立：门可过而装饰没提交属锁与句柄那一支，门不过则属邻居那一支。
        String gate = DependencyTable.neighborGateDetail(chunk);
        StringBuilder text = new StringBuilder();
        text.append("FLSTALL sample dim=").append(dimension.identifier())
                .append(" chunk=").append(cx).append(',').append(cz)
                .append(" dist=").append(distance)
                .append(" wrDone=").append(ChunkCounters.isWindowReadDone(chunk))
                .append(" light=").append(OwnershipTable.isLightedClaimed(cx, cz))
                .append(" ter=").append(OwnershipTable.isChunkClaimed(OwnershipTable.TERRAIN, pos.pack()))
                .append(" deco=").append(OwnershipTable.isChunkClaimed(OwnershipTable.DECORATED, pos.pack()))
                .append(" stages=").append(Arrays.toString(histogram))
                .append(" secs=").append(sections.length)
                .append(" ny=").append(counts[0])
                .append(" nyIn=").append(counts[2])
                .append(" sub=").append(Scheduler.submitTrace(pos.pack()))
                .append(" exit=").append(Scheduler.exitTrace(pos.pack()))
                .append(" unproc=").append(Arrays.toString(Arrays.copyOf(unprocessed, counts[1])))
                .append(" gate=").append(gate == null ? "ok" : gate);
        if (counts[2] < counts[0]) {
            // 有未处理段落在段集合之外，把窗口并集一起打出来，好分开「段号在窗口外」与「被别的过滤挡掉」。
            text.append(" win=").append(Arrays.toString(EntitySectionWindow.ranges(dimension)));
        }
        InfsFarlands.LOGGER.info(text.toString());
    }
}
