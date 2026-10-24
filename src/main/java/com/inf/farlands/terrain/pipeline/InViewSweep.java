package com.inf.farlands.terrain.pipeline;

import java.util.List;

import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.PlayerSnapshot;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

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

    private InViewSweep() {
    }

    /** 每 200 tick 一次，由 {@code FarlandsTick} 的周期块调用。关服期直接返回。 */
    public static void run(MinecraftServer server) {
        if (ChunkReadiness.isShuttingDown()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            sweep(level);
        }
    }

    /** 扫一个维度并补提交。该维度没有玩家时扫出来是零格，什么都不做。 */
    private static void sweep(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        List<PlayerSnapshot.Entry> entries = PlayerSnapshot.entries();
        int radius = PlayerSnapshot.viewDistance(dimension) + 1;
        LongOpenHashSet seen = new LongOpenHashSet();
        int submitted = 0;
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
                    LevelChunk chunk = ChunkReadiness.chunkAt(level, new ChunkPos(cx, cz));
                    if (chunk == null) {
                        continue;
                    }
                    if (Scheduler.hasCandidate(chunk)) {
                        continue;
                    }
                    int[] sections = Scheduler.sectionsFor(chunk);
                    if (sections.length == 0
                            || DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS) == 0) {
                        continue;
                    }
                    if (submitted < SUBMIT_LIMIT) {
                        Scheduler.submitQuiet(chunk);
                        submitted++;
                    }
                }
            }
        }
    }
}
