package com.inf.farlands.serialize;

import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.pipeline.Scheduler;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * fsa 与 terrain 的接线点。
 *
 * <p>三条：
 *
 * <ul>
 * <li>{@code isChunkBusy} 与 {@code isBiomeFilling} 是「此刻不能序列化这个段」的两个判据，前者读
 * 所有权表的三相，后者读群系在途集合。</li>
 * <li>{@code enqueueGen} 是读回完成之后的信号：通知调度器该 chunk 在窗口内可能想要工作。入队的判据
 * 在调度器的准入，本类不做过滤。</li>
 * </ul>
 */
public final class TerrainHooks {

    private TerrainHooks() {
    }

    public static boolean isChunkBusy(LevelChunk chunk) {
        return GenQueue.isChunkBusy(chunk);
    }

    /** 群系填充在途。与 isChunkBusy 一起构成「此刻不能序列化这个段」的判据。 */
    public static boolean isBiomeFilling(LevelChunk chunk) {
        return GenQueue.isBiomeFilling(chunk);
    }

    /** 读回完成后的信号。sectionY 只表达语义，调度器按窗口并集或面重算范围。 */
    public static void enqueueGen(LevelChunk chunk, int sectionY) {
        Scheduler.submit(chunk);
    }
}
