package com.inf.farlands.client.simulation;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 一个模拟方块：方块状态加它自己的碰撞形状。
 *
 * <p>
 * 形状用块局部坐标，范围 0 到 1，与 vanilla 的 block shape 同口径，允许任意内缩盒。整格用
 * {@link #full(BlockState)} 取 {@code Shapes.block()}。
 */
public record SimulationBlock(BlockState state, VoxelShape shape) {

    public SimulationBlock {
        if (state == null || shape == null) {
            throw new IllegalArgumentException("farlands: simulation block needs both state and shape");
        }
    }

    /** 整格方块。 */
    public static SimulationBlock full(BlockState state) {
        return new SimulationBlock(state, Shapes.block());
    }
}
