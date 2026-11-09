package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.Density;
import com.inf.farlands.simulation.int64.noise.NoiseHolder;

/**
 * 噪声节点。vanilla 的 {@code DensityFunctions.Noise}。
 *
 * <p>坐标在这里一次性从 long 转成 double 再乘缩放：{@code (double) x * xzScale} 与 vanilla 的
 * {@code blockX * xzScale} 同式，int32 内逐位一致。这是整条链上唯一的坐标转换点。
 *
 * <p>三个缩放里 {@code yScale} 作用于 Y，{@code xzScale} 同时作用于 X 与 Z，与 vanilla 相同。
 */
public record NoiseDensity(NoiseHolder noise, double xzScale, double yScale) implements Density {

    @Override
    public double compute(long x, long y, long z) {
        return this.noise.getValue((double) x * this.xzScale, (double) y * this.yScale,
                (double) z * this.xzScale);
    }

    @Override
    public double minValue() {
        return -this.noise.maxValue();
    }

    @Override
    public double maxValue() {
        return this.noise.maxValue();
    }

    @Override
    public Density mapAll(Visitor visitor) {
        return visitor.apply(new NoiseDensity(visitor.visitNoise(this.noise), this.xzScale, this.yScale));
    }
}
