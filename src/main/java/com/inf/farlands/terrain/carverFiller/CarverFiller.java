package com.inf.farlands.terrain.carverFiller;

import com.inf.farlands.light.IColumnMasks;
import com.inf.farlands.mixin.noise.HeightmapInvoker;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * CARVERS 阶段编排：何时跑、状态推进到 CARVERS、按维度分派 CarverSystem。
 *
 * carver 依赖目标 chunk 的 fill 与 surface 产物，必须紧跟 surface 且同 chunk 串行，由 GenTask
 * 内保证。目标 chunk 为中心的 17x17 起点网格只查 biomeSource 与确定性随机，不依赖邻居生成状态。
 *
 * 触发：GenTask surface 完成后调 applyCarversIfNeeded。补触发：surface 成功但 carvers 失败的
 * section 停留 SURFACE，由调度器的准入在段全到 SURFACE 后重发整 chunk 作业。
 *
 * 失败策略同 SurfaceFiller：异常由 GenTask 捕获，不抛，section 停留 SURFACE 由补触发重试。
 */
public final class CarverFiller {

    private CarverFiller() {
    }

    /**
     * 该 chunk 是否已有 SURFACE 未 CARVERS 且非读回的 section。
     *
     * <p>读回在途的段不算待处理：它返回假时调用方直接返回空数组，那些段既不雕刻也不标脏，只等读回完成
     * 后的回调再触发入队。
     */
    public static boolean hasCarversPending(LevelChunk chunk) {
        for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            if (!WorldBounds.inSection(sy)) {
                continue;
            }
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.SURFACE)
                    && !SectionStage.isOrAfter(chunk, sy, SectionStage.CARVERS)
                    && !SectionIO.isReading(chunk, sy)) {
                return true;
            }
        }
        return false;
    }

    /** 编排：有 carvers 待处理才跑，维度系统应用加升 CARVERS 加标脏加高度图 prime。 */
    public static int[] applyCarversIfNeeded(ServerLevel level, LevelChunk chunk) {
        if (!hasCarversPending(chunk)) {
            return new int[0];
        }
        long t0 = System.nanoTime();
        ((LevelSystems) level).carverSystem().applyCarvers(level, chunk);
        // carve 会修改方块，必须标脏，否则写盘丢雕刻结果。fill 与 surface 已标脏的重复标无害。
        List<Integer> list = new ArrayList<>();
        for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.SURFACE)
                    && !SectionStage.isOrAfter(chunk, sy, SectionStage.CARVERS)) {
                list.add(sy);
            }
        }
        int[] carved = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            carved[i] = list.get(i);
        }
        Scheduler.stage(chunk, carved, SectionStage.CARVERS);
        Scheduler.dirty(chunk, carved, true);
        if (carved.length > 0) {
            primeFinalHeightmaps(chunk);
        }
        StageMetrics.stageWork(StageMetrics.STAGE_CARVERS, System.nanoTime() - t0);
        StageMetrics.stageSections(StageMetrics.STAGE_CARVERS, carved.length);
        return carved;
    }

    // 自研最终高度图 prime，规避 vanilla primeHeightmaps 的极端 Y 扫描炸弹

    /** CARVERS 后 prime 的四种最终高度图，即 ChunkStatus CARVERS 的 heightmapsAfter。 */
    // private static final Heightmap.Types[] FINAL_TYPES = {
    // Heightmap.Types.OCEAN_FLOOR,
    // Heightmap.Types.WORLD_SURFACE,
    // Heightmap.Types.MOTION_BLOCKING,
    // Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
    // };

    /**
     * 一段与它的对象。两遍之间必须用同一个对象：第二遍现取会被主线程的清理删成 null。
     */
    private record NonEmptySection(int sectionY, LevelChunkSection section) {
    }

    /**
     * 自研 prime：每列从非空 section 顶部向下找各类型的最高匹配行。
     * 对齐 vanilla primeHeightmaps 语义，opaque 判定，首个匹配行设 height 为 y 加 1，
     * 全列无匹配则不设，保持默认。列掩码位跳只访问非空行，复杂度是非空 section 数乘 256。
     */
    public static void primeFinalHeightmaps(LevelChunk chunk) {
        WindowedChunk wc = (WindowedChunk) chunk;
        List<NonEmptySection> nonEmpty = new ArrayList<>();
        for (Map.Entry<Integer, LevelChunkSection> e : wc.windowedAllSections().entrySet()) {
            LevelChunkSection s = e.getValue();
            if (s != null && !s.hasOnlyAir()) {
                nonEmpty.add(new NonEmptySection(e.getKey(), s));
            }
        }
        nonEmpty.sort((a, b) -> Integer.compare(b.sectionY(), a.sectionY()));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Set<Heightmap.Types> remaining = EnumSet.of(
                        Heightmap.Types.OCEAN_FLOOR,
                        Heightmap.Types.WORLD_SURFACE,
                        Heightmap.Types.MOTION_BLOCKING,
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
                for (NonEmptySection entry : nonEmpty) {
                    if (remaining.isEmpty()) {
                        break;
                    }
                    int sy = entry.sectionY();
                    LevelChunkSection s = entry.section();
                    short[] masks = ((IColumnMasks) s).farlands$ensureColumnMasks();
                    int m = masks[x + z * 16] & 0xFFFF;
                    while (m != 0 && !remaining.isEmpty()) {
                        int k = 31 - Integer.numberOfLeadingZeros(m);
                        m ^= 1 << k;
                        int y = sy * 16 + k;
                        BlockState state = s.getBlockState(x, k, z);
                        Iterator<Heightmap.Types> it = remaining.iterator();
                        while (it.hasNext()) {
                            Heightmap.Types type = it.next();
                            if (type.isOpaque().test(state)) {
                                ((HeightmapInvoker) chunk.getOrCreateHeightmapUnprimed(type))
                                        .farlands$setHeight(x, z, y + 1);
                                it.remove();
                            }
                        }
                    }
                }
            }
        }
    }
}
