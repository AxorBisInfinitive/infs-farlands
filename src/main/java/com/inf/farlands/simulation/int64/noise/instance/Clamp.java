package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.Density;

import net.minecraft.util.Mth;

/**
 * 值域裁剪。vanilla 的 {@code DensityFunctions.Clamp}。
 *
 * <p>{@code mapAll} 只替换入参、不再调 {@code visitor.apply}，与 vanilla 一致。同一份 vanilla 里
 * {@code Constant} 与 {@code NoiseDensity} 是调 apply 的，两者不能统一处理。
 *
 * <p>两个分量名 {@code minValue} 与 {@code maxValue} 同时充当接口访问器，因此不必另写。
 */
public record Clamp(Density input, double minValue, double maxValue) implements Density {

    @Override
    public double compute(long x, long y, long z) {
        return Mth.clamp(this.input.compute(x, y, z), this.minValue, this.maxValue);
    }

    @Override
    public Density mapAll(Visitor visitor) {
        return new Clamp(this.input.mapAll(visitor), this.minValue, this.maxValue);
    }
}
