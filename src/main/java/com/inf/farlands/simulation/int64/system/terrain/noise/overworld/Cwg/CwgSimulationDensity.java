package com.inf.farlands.simulation.int64.system.terrain.noise.overworld.Cwg;

import com.inf.farlands.simulation.int64.noise.Density;
import com.inf.farlands.simulation.int64.noise.InterpolatedDensity;

/**
 * Cwg 的密度链，地形层 CwgDensity 的复制体。
 *
 * <p>
 * 算子顺序是契约，颠倒会改变结果：selector 决定 low 与 high 之间的插值位置，depth 那一路先钳到
 * [-2, 1] 再按正负分两段缩放，最后整体乘 volatility、加 height、减 sign(volatility) 乘 y。depth 的缩放
 * 必须先钳后分，钳在分之后会让干燥侧的系数落到湿润侧。
 *
 * <p>
 * 与地形层那份的差别只有两处，都在载体上：坐标是 long 方块坐标，进来一次性转 double，int32 范围内
 * {@code (double) (long) x} 与 {@code (double) (int) x} 恒等，所以两条链在 int32 域内逐位相同；height 与
 * volatility 的来源是构造参数，不是两条形状样条，模拟层没有与地形层同源的 continents、erosion、ridges。
 *
 * <p>
 * 十三个缩放与偏移来自系统的参数声明，由 {@link Ops} 一次带过。它们不影响穹顶：冻结区里 blended 的量级远
 * 大于 height 与 y，密度的符号即 blended 的符号，零集仍是那张平面。
 *
 * <p>
 * 无状态：实例被多个填充任务共享，字段全部 final，compute 只读。minValue 与 maxValue 取正负 1e300 而不是
 * double 的上下界，因为上层会把密度与其它项相加，用上界会在求和处溢出成无穷。
 */
public final class CwgSimulationDensity implements InterpolatedDensity {

    /** 十三个缩放与偏移，顺序与 farlands 的 {@code CwgDensity.Ops} 一致。 */
    public record Ops(double selectorNoiseFactor, double selectorNoiseOffset, double lowNoiseFactor,
            double lowNoiseOffset, double highNoiseFactor, double highNoiseOffset, double depthNoiseFactor,
            double depthNoiseOffset, double heightFactor, double heightOffset, double heightVariationFactor,
            double heightVariationOffset, double specialHeightVariationFactorBelowAverageY) {
    }

    private final CwgSimulationNoise selector;
    private final CwgSimulationNoise low;
    private final CwgSimulationNoise high;
    private final CwgSimulationNoise depth;
    private final double heightSource;
    private final double factorSource;
    private final Ops ops;

    public CwgSimulationDensity(CwgSimulationNoise selector, CwgSimulationNoise low, CwgSimulationNoise high,
            CwgSimulationNoise depth, double heightSource, double factorSource, Ops ops) {
        this.selector = selector;
        this.low = low;
        this.high = high;
        this.depth = depth;
        this.heightSource = heightSource;
        this.factorSource = factorSource;
        this.ops = ops;
    }

    @Override
    public double compute(long x, long y, long z) {
        double dx = (double) x;
        double dy = (double) y;
        double dz = (double) z;

        double selectorValue = this.selector.normalized(dx, dy, dz) * this.ops.selectorNoiseFactor()
                + this.ops.selectorNoiseOffset();
        selectorValue = selectorValue < 0.0 ? 0.0 : (selectorValue > 1.0 ? 1.0 : selectorValue);

        double lowValue = this.low.normalized(dx, dy, dz) * this.ops.lowNoiseFactor() + this.ops.lowNoiseOffset();
        double highValue = this.high.normalized(dx, dy, dz) * this.ops.highNoiseFactor()
                + this.ops.highNoiseOffset();

        double depthValue = this.depth.normalized(dx, dy, dz) * this.ops.depthNoiseFactor()
                + this.ops.depthNoiseOffset();
        if (depthValue < 0.0) {
            depthValue *= -0.3;
        }
        depthValue = depthValue * 3.0 - 2.0;
        depthValue = depthValue < -2.0 ? -2.0 : (depthValue > 1.0 ? 1.0 : depthValue);
        if (depthValue < 0.0) {
            depthValue /= 2.0 * 2.0 * 1.4;
        } else if (depthValue > 0.0) {
            depthValue /= 8.0;
        }
        depthValue *= 0.2 * 17.0 / 64.0;

        double height = this.heightSource * this.ops.heightFactor() + this.ops.heightOffset();
        double variation = Math.abs(this.factorSource);
        if (height > dy) {
            variation *= this.ops.specialHeightVariationFactorBelowAverageY();
        }
        double volatility = variation * this.ops.heightVariationFactor() + this.ops.heightVariationOffset();

        double blended = lowValue + selectorValue * (highValue - lowValue);
        return (blended + depthValue) * volatility + height - Math.signum(volatility) * dy;
    }

    @Override
    public double minValue() {
        return -1.0E300;
    }

    @Override
    public double maxValue() {
        return 1.0E300;
    }

    @Override
    public Density mapAll(Visitor visitor) {
        return visitor.apply(this);
    }
}
