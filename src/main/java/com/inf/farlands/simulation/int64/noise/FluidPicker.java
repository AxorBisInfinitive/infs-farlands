package com.inf.farlands.simulation.int64.noise;

/**
 * 液面查询，与 vanilla 的 {@code Aquifer.FluidPicker} 同形，只是坐标是 long。
 */
@FunctionalInterface
public interface FluidPicker {

    FluidStatus computeFluid(long x, long y, long z);
}
