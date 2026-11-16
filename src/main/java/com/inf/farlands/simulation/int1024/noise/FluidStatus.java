package com.inf.farlands.simulation.int1024.noise;

import com.inf.long2.ints.Int1024;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一处液面：液位加流体方块。
 *
 * <p>
 * 与 vanilla 的 {@code Aquifer.FluidStatus} 同形，液位是 1024 位坐标。{@link #at} 的判据是严格小于，所以
 * {@code Int1024.MIN_VALUE} 作哨兵表示「任何 y 都取不到」。
 */
public record FluidStatus(Int1024 fluidLevel, BlockState fluidType) {

    public BlockState at(Int1024 y) {
        return y.compareTo(this.fluidLevel) < 0 ? this.fluidType : Blocks.AIR.defaultBlockState();
    }
}
