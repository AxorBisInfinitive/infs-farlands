package com.inf.farlands.debug;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.pipeline.DependencyTable;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.util.window.PlayerSnapshot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 诊断：候选离开候选表的读数。临时仪器，取到读数后与同批探针一并删除。
 *
 * <p>候选离开候选表有两条静默路：{@code FACE} 与 {@code WINDOW} 被拒后直接 {@code continue}，一次性
 * 移除；其余四条经 {@code requeue}，退避重投到上限之后丢弃。两条都不计数，所以停摆时看不出丢了多少。
 *
 * <p>{@code droppedWithWork} 是靶心：丢弃那一刻该 chunk 仍有低于 {@code CARVERS} 的段，才是真的丢活；
 * 全是零则说明缺的段从未进过候选表，问题在上游提交。
 *
 * <p>逐条只在真的丢弃时打，且每格只打第一次、总数有上限；退出计数全记。每 200 tick 由
 * {@code FarlandsTick} 打一行汇总。全部挂在 {@code logScheduler} 上。
 */
public final class CandidateProbe {

    /** 逐条输出上限。 */
    private static final int EVENT_LIMIT = 200;

    /** 退出原因到次数。 */
    private static final Map<String, AtomicLong> EXITS = new ConcurrentHashMap<>();

    /** 被丢弃的候选数，含一次性移除与退避耗尽。 */
    private static final AtomicLong DROPPED = new AtomicLong();

    /** 其中丢弃时仍有低于 CARVERS 的段。 */
    private static final AtomicLong DROPPED_WITH_WORK = new AtomicLong();

    /** 其中由退避耗尽造成的。 */
    private static final AtomicLong EXHAUSTED = new AtomicLong();

    /** 已被丢弃过的 chunk 数。 */
    private static final Set<Long> CHUNKS = ConcurrentHashMap.newKeySet();

    /** 已输出的逐条行数。 */
    private static final AtomicLong EVENTS = new AtomicLong();

    /** 已逐条打过的 chunk，防同一格反复打。 */
    private static final Set<Long> LOGGED = ConcurrentHashMap.newKeySet();

    private CandidateProbe() {
    }

    /**
     * 退避重投到上限、候选就此被丢弃。与 {@link #left} 分开，因为这条是「等过头」，与一次性移除不是
     * 同一个形态。
     */
    public static void exhausted(LevelChunk chunk, String exit, int attempts) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        EXHAUSTED.incrementAndGet();
        left(chunk, exit, attempts, true);
    }

    /**
     * 一个候选离开候选表。
     *
     * @param chunk     该候选
     * @param exit      退出原因，六个之一
     * @param attempts  退出时已重投的次数，一次性移除记 1
     * @param dropped   是否就此丢弃，不再重投
     */
    public static void left(LevelChunk chunk, String exit, int attempts, boolean dropped) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        EXITS.computeIfAbsent(exit, k -> new AtomicLong()).incrementAndGet();
        if (!dropped) {
            return;
        }
        DROPPED.incrementAndGet();
        long key = chunk.getPos().pack();
        CHUNKS.add(key);
        int[] sections = Scheduler.sectionsFor(chunk);
        int pending = DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS);
        if (pending > 0) {
            DROPPED_WITH_WORK.incrementAndGet();
        }
        if (!LOGGED.add(key)) {
            return;
        }
        long events = EVENTS.incrementAndGet();
        if (events > EVENT_LIMIT) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLDROP chunk={},{} exit={} attempts={} pk={} n={} dist={} loaded={} stages={}",
                chunk.getPos().x(), chunk.getPos().z(), exit, attempts, pending, sections.length,
                distanceOf(chunk), loaded(chunk), stageHistogram(chunk));
    }

    /** 到最近玩家的距离；拿不到 level 时记 -1。 */
    private static int distanceOf(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            return PlayerSnapshot.nearestDistance(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
        }
        return -1;
    }

    /** 该格当前是否已加载到至少允许 TERRAIN 的程度；用 holder 在场近似。 */
    private static boolean loaded(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            return com.inf.farlands.serialize.SectionLifecycle.latestHolder(level, chunk.getPos().x(),
                    chunk.getPos().z()) != null;
        }
        return false;
    }

    /** 档位直方图，下标即档位。 */
    private static String stageHistogram(LevelChunk chunk) {
        int[] counts = new int[SectionStage.LIGHTED + 1];
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage >= 0 && stage < counts.length) {
                counts[stage]++;
            }
        });
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(counts[i]);
        }
        return sb.append(']').toString();
    }

    /** 打印并清零本窗计数。 */
    public static void flush() {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        StringBuilder exits = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, AtomicLong> e : EXITS.entrySet()) {
            long n = e.getValue().getAndSet(0L);
            if (n == 0L) {
                continue;
            }
            if (!first) {
                exits.append(' ');
            }
            exits.append(e.getKey()).append('=').append(n);
            first = false;
        }
        exits.append(']');
        InfsFarlands.LOGGER.info(
                "FLDROP sum exits={} dropped={} droppedWithWork={} exhausted={} chunks={} events={}",
                exits, DROPPED.getAndSet(0L), DROPPED_WITH_WORK.getAndSet(0L), EXHAUSTED.getAndSet(0L),
                CHUNKS.size(), EVENTS.getAndSet(0L));
    }
}
