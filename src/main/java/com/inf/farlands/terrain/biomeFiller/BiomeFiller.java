package com.inf.farlands.terrain.biomeFiller;

import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * BIOMES 阶段编排：何时填、状态推进到 BIOMES、按维度分派 BiomeSystem。
 *
 * biome 是独立阶段，不依赖地形内容，由触发链驱动：短路完成时由 {@code farlandsGenerateTail} 在驱动
 * 面内按窗口并集铺，其余入口是生成收段前的补种、出生区预加载与 {@code /fillbiome}，都是单段。fill
 * 不再管 biome，读回 section 的磁盘 stage 恢复后自动跳过。
 *
 * setStage 线程安全：stage 载体是分段 map 加 CHM，任意线程可写。
 */
public final class BiomeFiller {

    private BiomeFiller() {
    }

    /**
     * 短路完成：窗口并集内每 section 填 biome 并升 BIOMES。后台线程。
     *
     * <p>调用方只对驱动面内的 chunk 调它，见 {@code farlandsGenerateTail}：视距之外铺了也不会被驱动。
     * 窗口并集不夹段界，段界之外的段由 {@link #seedSection} 跳过。
     */
    public static void fillChunkBiomes(ServerLevel level, LevelChunk chunk) {
        BiomeSystem sys = ((LevelSystems) level).biomeSystem();
        EntitySectionWindow.forEachSectionInAnyWindow(level.dimension(), sy -> seedSection(level, chunk, sys, sy));
    }

    /** 窗口滑入补触发：单 section，stage 小于 BIOMES 才填。主线程。 */
    public static void fillSectionBiomes(ServerLevel level, LevelChunk chunk, int sectionY) {
        if (SectionStage.getStage(chunk, sectionY) < SectionStage.BIOMES) {
            seedSection(level, chunk, ((LevelSystems) level).biomeSystem(), sectionY);
        }
    }

    /**
     * 单段填充，对同一个 chunk 串行，并在锁内判 stage。
     *
     * <p>填充有三个来源会并发：短路完成那条跑在 {@code Util.backgroundExecutor}，窗口滑入那条在
     * 主线程，GenTask 里补种那条在 genPool。而
     * {@code LevelChunkSection.fillBiomesFromNoise} 是 {@code recreate()} 加 64 次
     * {@code getAndSetUnchecked} 再整体换引用，两个线程同时跑会得到一份混合网格（一半 one 次求值、
     * 一半另一次）；stage 的读改写同样需要原子。锁加在 chunk 上，一次填充一次，成本可忽略。
     *
     * <p>段界与 fill 的取数面同宽：fill 只吃 {@code WorldBounds.inSection} 的段，界外的段种了也推不动，
     * 而它会永久留在 stage 表里，把装饰的九宫格门恒挡成假。判断收口在这唯一的写 stage 点，四处调用点
     * 不必各自过滤。
     */
    private static void seedSection(ServerLevel level, LevelChunk chunk, BiomeSystem sys, int sectionY) {
        if (!WorldBounds.inSection(sectionY)) {
            return;
        }
        synchronized (chunk) {
            if (SectionStage.getStage(chunk, sectionY) >= SectionStage.BIOMES) {
                return;
            }
            long t0 = System.nanoTime();
            sys.fillBiomes(level, chunk, sectionY, sectionY);
            StageMetrics.stageWork(StageMetrics.STAGE_BIOMES, System.nanoTime() - t0);
            SectionStage.setStage(chunk, sectionY, SectionStage.BIOMES);
            StageMetrics.sectionsIn(1L);
            StageMetrics.stageSections(StageMetrics.STAGE_BIOMES, 1);
        }
    }
}
