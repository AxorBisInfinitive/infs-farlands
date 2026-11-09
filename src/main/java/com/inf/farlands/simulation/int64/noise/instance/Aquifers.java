package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.Aquifer;
import com.inf.farlands.simulation.int64.noise.FluidPicker;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 默认含水层。vanilla 的 {@code Aquifer.createDisabled}。
 *
 * <p>判据只有一句：密度为正返回 null，表示实方块交给默认方块；否则按液面查询取流体或空气。空空气由这里
 * 自己返回，不由调用方按密度符号补。
 *
 * <p>{@code shouldScheduleFluidUpdate} 恒假：这一层不做流体结算。
 */
public final class Aquifers {

    private Aquifers() {
    }

    public static Aquifer disabled(FluidPicker picker) {
        return new Aquifer() {

            @Override
            public BlockState computeSubstance(long x, long y, long z, double density) {
                return density > 0.0 ? null : picker.computeFluid(x, y, z).at(y);
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return false;
            }
        };
    }
}
