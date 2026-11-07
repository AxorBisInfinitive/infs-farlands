package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.FluidPicker;
import com.inf.farlands.simulation.int64.noise.FluidStatus;

import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 默认液面查询。vanilla 的 {@code NoiseBasedChunkGenerator.createFluidPicker}。
 *
 * <p>三态与判断照抄：-54 以下取岩浆，海平面以下取流体，其余空气。{@code DEBUG_DISABLE_FLUID_GENERATION}
 * 打开时一律空，那是共享的调试开关，不是本层的判据。
 *
 * <p>vanilla 的空气态液位取 {@code DimensionType.MIN_Y * 2}，本 port 的竖直无界，没有那个维度值可乘，
 * 取 {@code Long.MIN_VALUE}。两者在可达坐标上的行为相同。
 */
public final class FluidPickers {

    private FluidPickers() {
    }

    public static FluidPicker standard(long seaLevel, BlockState defaultFluid) {
        FluidStatus lava = new FluidStatus(-54L, Blocks.LAVA.defaultBlockState());
        FluidStatus sea = new FluidStatus(seaLevel, defaultFluid);
        FluidStatus empty = new FluidStatus(Long.MIN_VALUE, Blocks.AIR.defaultBlockState());
        return (x, y, z) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION
                ? empty
                : (y < Math.min(-54L, seaLevel) ? lava : sea);
    }
}
