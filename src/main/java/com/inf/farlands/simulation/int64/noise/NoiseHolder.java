package com.inf.farlands.simulation.int64.noise;

/**
 * 噪声节点：声明加已建实例。
 *
 * <p>实例为 null 表示还没建，此时取值恒 0、上界 2.0，与 vanilla 的同一分支同值。
 */
public record NoiseHolder(NoiseData data, Noise noise) {

    public double getValue(double x, double y, double z) {
        return this.noise == null ? 0.0 : this.noise.getValue(x, y, z);
    }

    public double maxValue() {
        return this.noise == null ? 2.0 : this.noise.maxValue();
    }
}
