package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 样条节点，对应 26.3 的 {@code SplineFunction}。
 *
 * <p>26.3 的样条会把坐标采样结果按整块体积缓存，移植体没有体积缓冲，坐标每方块现取。值不变，
 * 只是多算。
 */
final class SplineNode263 extends FloatNode263 {

    private final CubicSpline263 spline;

    SplineNode263(CubicSpline263 spline) {
        this.spline = spline;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        return this.spline.sample(context);
    }
}
