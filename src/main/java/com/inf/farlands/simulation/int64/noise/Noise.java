package com.inf.farlands.simulation.int64.noise;

/**
 * 噪声求值面，与 vanilla 的 {@code NormalNoise} 同形。
 *
 * <p>只定义形状，实现不属这一层。
 */
public interface Noise {

    double getValue(double x, double y, double z);

    double maxValue();
}
