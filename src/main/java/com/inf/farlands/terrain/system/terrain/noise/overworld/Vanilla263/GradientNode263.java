package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 轴向梯度节点，对应 26.3 的 {@code GradientFunction} 的 {@code CLAMP_TO_EDGE} 形态。
 *
 * <p>整条主世界链只用 {@code yClampedGradient}，另外两种平铺方式与移位轴不在本路径上，因此不实现。
 *
 * <p>比例在构造期算一次且是 float 除法：{@code (toValue - fromValue) / (to - from)}，分母是 int。
 * 挪到调用点算会每方块重算一次，值不变但白花。
 */
final class GradientNode263 extends FloatNode263 {

    private final Direction.Axis axis;
    private final int fromCoordinate;
    private final int minCoordinate;
    private final int maxCoordinate;
    private final float fromValue;
    private final float coordinateFactor;

    GradientNode263(Direction.Axis axis, int fromCoordinate, int toCoordinate, float fromValue, float toValue) {
        this.axis = axis;
        this.fromCoordinate = fromCoordinate;
        this.minCoordinate = Math.min(fromCoordinate, toCoordinate);
        this.maxCoordinate = Math.max(fromCoordinate, toCoordinate);
        this.fromValue = fromValue;
        this.coordinateFactor = (toValue - fromValue) / (toCoordinate - fromCoordinate);
    }

    /** {@code yClampedGradient} 的等价构造。 */
    static GradientNode263 yClamped(int fromY, int toY, float fromValue, float toValue) {
        return new GradientNode263(Direction.Axis.Y, fromY, toY, fromValue, toValue);
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        int coordinate = this.axis.choose(context.blockX(), context.blockY(), context.blockZ());
        int relative = Mth.clamp(coordinate, this.minCoordinate, this.maxCoordinate) - this.fromCoordinate;
        return this.fromValue + relative * this.coordinateFactor;
    }
}
