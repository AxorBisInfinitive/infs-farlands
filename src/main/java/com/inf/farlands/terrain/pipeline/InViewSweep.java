package com.inf.farlands.terrain.pipeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.PlayerSnapshot;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 视距内的兜底重扫：把「有活、却不在候选表」的 chunk 补交一次。
 *
 * <p>存在的理由只有一条。准入的 {@code WINDOW} 判定是提交时刻的一次性判断，且它终止候选、不回表；而
 * 壳由大半径票铺到视距外十圈，创建流程对每个壳只提交一次。于是壳在远处被判太远、离表，玩家走近之后
 * 没有任何事件重新提交它，地形就永远不生成。横向移动只置玩家移动标志，不产生提交扇出；周期性重扫
 * 因此是这条链上唯一能补的口子。
 *
 * <p>扫的是「视距加一」的方阵，与 {@code GenQueue.isNearPlayer} 同源，所以补提交的 chunk 不会被
 * {@code WINDOW} 再拒。只补「段集合里还有低于 CARVERS 的段」的那些，也就是派发里 {@code gen} 那一支
 * 的判据；停在 CARVERS 或 DECORATED 的属装饰与光照那一族，它们由各自的重投管，补进候选表只会让它们
 * 按固定节奏长期占位。
 *
 * <p>线程：主线程，由 {@code FarlandsTick} 的 200 tick 块调用。关服期不扫。
 */
public final class InViewSweep {

    /** 每次扫描最多补提交多少个。实测最坏积压五百八十，按此上限三窗清完。 */
    private static final int SUBMIT_LIMIT = 256;

    /**
     * 本窗读数。诊断读取用，不参与任何判据。
     *
     * @param radius      扫描半径，即视距加一
     * @param scanned     扫过的格数
     * @param noShell     其中没有壳的格数
     * @param noStage     有壳但档位表为空的格数
     * @param belowLighted 档位表里有低于 LIGHTED 的段的格数
     * @param stuck       有欠账且不在候选表里的格数
     * @param stuckFull   其中票位不大于 FULL 档的格数，即提交了就会被准入放行
     * @param stuckAbove  其中票位大于 FULL 档的格数，即准入自己会拒
     * @param stuckNoHolder 其中取不到 holder 的格数
     * @param candInView  视距内落在候选表里的格数
     * @param submitted   本次补提交的格数
     * @param rings       未入表且需补提交的格按切比雪夫距离的分布
     * @param ringRep     每一环留住的一个代表格，供诊断取样
     */
    public record Stats(int radius, int scanned, int noShell, int noStage, int belowLighted,
            int stuck, int stuckFull, int stuckAbove, int stuckNoHolder, int candInView,
            int submitted, Int2IntOpenHashMap rings, int[][] ringRep) {
    }

    /** 每维度的本窗读数。 */
    private static final Map<ResourceKey<Level>, Stats> LAST = new ConcurrentHashMap<>();

    private InViewSweep() {
    }

    /** 该维度最近一窗的读数；没扫过时是 null。 */
    public static Stats last(ResourceKey<Level> dimension) {
        return LAST.get(dimension);
    }

    /** 每 200 tick 一次，由 {@code FarlandsTick} 的周期块调用。关服期直接返回。 */
    public static void run(MinecraftServer server) {
        if (ChunkReadiness.isShuttingDown()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            LAST.put(level.dimension(), sweep(level));
        }
    }

    /** 扫一个维度并补提交。该维度没有玩家时扫出来是零格，什么都不做。 */
    private static Stats sweep(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        List<PlayerSnapshot.Entry> entries = PlayerSnapshot.entries();
        int radius = PlayerSnapshot.viewDistance(dimension) + 1;
        LongOpenHashSet seen = new LongOpenHashSet();
        Int2IntOpenHashMap rings = new Int2IntOpenHashMap();
        int[][] ringRep = new int[radius + 1][];
        int scanned = 0;
        int noShell = 0;
        int noStage = 0;
        int belowLighted = 0;
        int stuck = 0;
        int stuckFull = 0;
        int stuckAbove = 0;
        int stuckNoHolder = 0;
        int candInView = 0;
        int submitted = 0;
        int[] counters = new int[2];
        int fullLevel = ChunkLevel.byStatus(ChunkStatus.FULL);
        boolean probe = com.inf.farlands.FarlandsConfig.logScheduler;
        for (PlayerSnapshot.Entry entry : entries) {
            if (!entry.dimension().equals(dimension)) {
                continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = entry.chunkX() + dx;
                    int cz = entry.chunkZ() + dz;
                    long key = ChunkPos.pack(cx, cz);
                    if (!seen.add(key)) {
                        continue;
                    }
                    scanned++;
                    LevelChunk chunk = ChunkReadiness.chunkAt(level, new ChunkPos(cx, cz));
                    if (chunk == null) {
                        noShell++;
                        continue;
                    }
                    boolean candidate = Scheduler.hasCandidate(chunk);
                    if (candidate) {
                        candInView++;
                    }
                    // 一次遍历同时得两个数：档位表有几项、其中几项低于 LIGHTED。空表是独立的一类。
                    counters[0] = 0;
                    counters[1] = 0;
                    SectionStage.forEachStage(chunk, (sy, stage) -> {
                        counters[0]++;
                        if (stage < SectionStage.LIGHTED) {
                            counters[1]++;
                        }
                    });
                    if (counters[0] == 0) {
                        noStage++;
                    } else if (counters[1] > 0) {
                        belowLighted++;
                    }
                    if (candidate) {
                        continue;
                    }
                    int[] sections = Scheduler.sectionsFor(chunk);
                    if (sections.length == 0
                            || DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS) == 0) {
                        continue;
                    }
                    stuck++;
                    if (probe) {
                        // 票位分带：不大于 FULL 档的，提交了就会被准入门放行，缺的是 submit；大于它的属
                        // 准入侧。这一栏只给诊断用，关掉读数时不付这次查表。
                        ChunkHolder holder = SectionLifecycle.latestHolder(level, cx, cz);
                        if (holder == null) {
                            stuckNoHolder++;
                        } else if (holder.getTicketLevel() <= fullLevel) {
                            stuckFull++;
                        } else {
                            stuckAbove++;
                        }
                    }
                    int distance = Math.max(Math.abs(dx), Math.abs(dz));
                    if (probe) {
                        rings.addTo(distance, 1);
                        if (distance < ringRep.length && ringRep[distance] == null) {
                            ringRep[distance] = new int[] { cx, cz };
                        }
                    }
                    if (submitted < SUBMIT_LIMIT) {
                        Scheduler.submitQuiet(chunk);
                        submitted++;
                    }
                }
            }
        }
        return new Stats(radius, scanned, noShell, noStage, belowLighted, stuck, stuckFull,
                stuckAbove, stuckNoHolder, candInView, submitted, rings, ringRep);
    }
}
