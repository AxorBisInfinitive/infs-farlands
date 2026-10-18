package com.inf.farlands.terrain.carverFiller;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * carver 阶段的方块写入，绕开 {@code ChunkAccess.setBlockState} 的副作用。
 *
 * <p>carve 跑在 farlands-gen 上，而 {@code ChunkAccess.setBlockState} 会走 {@code onPlace}：岩浆那一路
 * 落到 {@code LiquidBlock.shouldSpreadLiquid}，再经 {@code level.getFluidState} 对邻居 chunk 做阻塞取数。
 * 池线程上等的那个 FULL 要靠同一个池推进，池被占满即双向等死。所以这里直写 section，与 fill、
 * surface 两处同规，onPlace、broadcast 与计划刻一概不接。
 *
 * <p>段号按 {@code LevelHeightAccessor} 的窗口索引取，与 {@code chunk.getSection(int)} 同源；
 * 缺段由那个方法按需建出。
 */
public final class CarverSectionWrite {

    private CarverSectionWrite() {
    }

    /** 把 state 写进 pos 所在的段，局部坐标取低四位。 */
    public static void write(ChunkAccess chunk, BlockPos pos, BlockState state) {
        LevelChunkSection section = chunk.getSection(
                ((WindowedChunk) chunk).levelHeightAccessor().getSectionIndex(pos.getY()));
        if (section != null) {
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
        }
    }
}
