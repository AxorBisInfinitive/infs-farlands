package com.inf.farlands.simulation.int64.noise;

import com.inf.long2.ints.Int1024;

/**
 * 液面查询，与 vanilla 的 {@code Aquifer.FluidPicker} 同形，坐标是 1024 位方块坐标。
 */
@FunctionalInterface
public interface FluidPicker {

    FluidStatus computeFluid(Int1024 x, Int1024 y, Int1024 z);
}
