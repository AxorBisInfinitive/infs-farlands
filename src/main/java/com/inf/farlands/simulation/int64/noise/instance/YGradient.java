package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.Density;

import net.minecraft.util.Mth;

/**
 * 沿 Y 的线性渐变。vanilla 的 {@code DensityFunctions.YClampedGradient}。
 *
 * <p>两端是 long，取值时转 double 后交给 {@code Mth.clampedMap}，与 vanilla 传 int 的那个调用同式，
 * int32 内逐位一致。端点在 long 远处会因 double 的表示粒度变粗，那是钳制边界变粗，不会错位或抛异常。
 */
public record YGradient(long fromY, long toY, double fromValue, double toValue) implements Density {

    @Override
    public double compute(long x, long y, long z) {
        return Mth.clampedMap((double) y, (double) this.fromY, (double) this.toY, this.fromValue, this.toValue);
    }

    @Override
    public double minValue() {
        return Math.min(this.fromValue, this.toValue);
    }

    @Override
    public double maxValue() {
        return Math.max(this.fromValue, this.toValue);
    }

    @Override
    public Density mapAll(Visitor visitor) {
        return visitor.apply(this);
    }
}
