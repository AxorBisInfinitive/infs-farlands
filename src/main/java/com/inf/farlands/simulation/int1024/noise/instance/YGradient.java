package com.inf.farlands.simulation.int1024.noise.instance;

import com.inf.farlands.simulation.int1024.noise.Density;
import love.crysh.long2.ints.Int1024;

import net.minecraft.util.Mth;

/**
 * 沿 Y 的线性渐变。vanilla 的 {@code DensityFunctions.YClampedGradient}。
 *
 * <p>两端是 1024 位坐标，取值时转 double 后交给 {@code Mth.clampedMap}，与 vanilla 传 int 的那个调用同式。
 * 端点在远处会因 double 的表示粒度变粗，那是钳制边界变粗，不会错位或抛异常。
 */
public record YGradient(Int1024 fromY, Int1024 toY, double fromValue, double toValue) implements Density {

    @Override
    public double compute(Int1024 x, Int1024 y, Int1024 z) {
        return Mth.clampedMap(y.toDouble(), this.fromY.toDouble(), this.toY.toDouble(), this.fromValue,
                this.toValue);
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
