package com.inf.farlands.terrain.surfaceFiller;

import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * SURFACE 阶段编排：何时跑、状态推进到 SURFACE、按维度分派 SurfaceSystem。
 *
 * surface 依赖 fill 产出的高度图，必须紧跟 fill 且同 chunk 串行，由 GenTask 内保证。
 *
 * 触发：GenTask fill 完成后调 applySurfaceIfNeeded，新 fill 的 section 必然是 TERRAIN 未
 * SURFACE。补触发：读回 stage 为 TERRAIN 的 section 由调度器的准入重发整 chunk 作业。
 *
 * 失败策略：applySurface 异常由 GenTask 捕获，section 停留 TERRAIN，下一次提交的准入会重试。
 *
 * 遍历面取「容器并档位表」，见 {@link SectionStage#sectionYs}：档位可以落在容器里没有条目的段上，
 * 非噪声生成器的维度里 fill 早退、一个段都没物化，而生成任务照样把整条 band 落到 TERRAIN。只遍历
 * 容器会让这类段永远停在 TERRAIN，而准入按档位判欠账，该 chunk 因此被无限放行。
 * 标脏只用容器内的段：容器里没有条目的段编码时取不到，脏标记会永久残留。
 */
public final class SurfaceFiller {

    private SurfaceFiller() {
    }

    /** 该 chunk 是否已有 TERRAIN 未 SURFACE 且非读回的 section。 */
    public static boolean hasSurfacePending(LevelChunk chunk) {
        for (int sy : SectionStage.sectionYs(chunk)) {
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.TERRAIN)
                    && !SectionStage.isOrAfter(chunk, sy, SectionStage.SURFACE)
                    && !SectionIO.isReading(chunk, sy)) {
                return true;
            }
        }
        return false;
    }

    /** 编排：有 surface 待处理才跑，维度系统应用加升 SURFACE 加标脏。返回本次升段的 sectionY。 */
    public static int[] applySurfaceIfNeeded(ServerLevel level, LevelChunk chunk) {
        if (!hasSurfacePending(chunk)) {
            return new int[0];
        }
        long t0 = System.nanoTime();
        ((LevelSystems) level).surfaceSystem().applySurface(level, chunk);
        StageMetrics.stageWork(StageMetrics.STAGE_SURFACE, System.nanoTime() - t0);
        // surface 修改方块，读回的 section 未标脏，不标就会写盘丢 surface 结果。
        // fill 已标脏的重复标无害，幂等。
        Set<Integer> inContainer = new HashSet<>(((WindowedChunk) chunk).windowedAllSections().keySet());
        List<Integer> list = new ArrayList<>();
        List<Integer> dirty = new ArrayList<>();
        for (int sy : SectionStage.sectionYs(chunk)) {
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.TERRAIN)
                    && !SectionStage.isOrAfter(chunk, sy, SectionStage.SURFACE)) {
                list.add(sy);
                if (inContainer.contains(sy)) {
                    dirty.add(sy);
                }
            }
        }
        int[] surfaced = toArray(list);
        Scheduler.stage(chunk, surfaced, SectionStage.SURFACE);
        Scheduler.dirty(chunk, toArray(dirty), true);
        StageMetrics.stageSections(StageMetrics.STAGE_SURFACE, surfaced.length);
        return surfaced;
    }

    /** 列表转数组。 */
    private static int[] toArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
