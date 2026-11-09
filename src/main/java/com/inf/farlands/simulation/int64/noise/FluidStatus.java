package com.inf.farlands.simulation.int64.noise;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一处液面：液位加流体方块。
 *
 * <p>与 vanilla 的 {@code Aquifer.FluidStatus} 同形，只是液位是 long。{@link #at} 的判据是严格小于。
 */
public record FluidStatus(long fluidLevel, BlockState fluidType) {

    public BlockState at(long y) {
        return y < this.fluidLevel ? this.fluidType : Blocks.AIR.defaultBlockState();
    }
}
