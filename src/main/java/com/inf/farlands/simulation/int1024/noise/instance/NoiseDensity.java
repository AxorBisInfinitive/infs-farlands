package com.inf.farlands.simulation.int1024.noise.instance;

import com.inf.farlands.simulation.int1024.noise.Density;
import com.inf.farlands.simulation.int1024.noise.NoiseHolder;
import love.crysh.long2.ints.Int1024;

/**
 * 噪声节点。vanilla 的 {@code DensityFunctions.Noise}。
 *
 * <p>坐标在这里一次性从 1024 位转成 double 再乘缩放，是整条链上唯一的坐标转换点。1050 位以内的整数都能落进
 * double 的有限域，量级再大也只在末位丢精度，不产生无穷。
 *
 * <p>三个缩放里 {@code yScale} 作用于 Y，{@code xzScale} 同时作用于 X 与 Z，与 vanilla 相同。
 */
public record NoiseDensity(NoiseHolder noise, double xzScale, double yScale) implements Density {

    @Override
    public double compute(Int1024 x, Int1024 y, Int1024 z) {
        return this.noise.getValue(x.toDouble() * this.xzScale, y.toDouble() * this.yScale,
                z.toDouble() * this.xzScale);
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
