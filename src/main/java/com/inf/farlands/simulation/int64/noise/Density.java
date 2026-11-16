package com.inf.farlands.simulation.int64.noise;

import com.inf.long2.ints.Int1024;

/**
 * 密度函数，坐标是 1024 位方块坐标。
 *
 * <p>
 * 与 vanilla 的 {@code DensityFunction} 同构：求值、两个界、以及 {@link #mapAll}。差别有三处：坐标宽度是
 * {@link Int1024}；不带 codec，这一层不序列化；不带 fillArray，这一层不做 cell 插值。
 *
 * <p>
 * 求值本身仍走 double：坐标在本接口的实现里一次性转成 double 再进噪声，转点在 {@code NoiseDensity}。
 */
public interface Density {

    double compute(Int1024 x, Int1024 y, Int1024 z);

    double minValue();

    double maxValue();

    /**
     * 自底向上替换节点。
     *
     * <p>语义与 {@code DensityFunction.mapAll} 相同：实现先把子节点各自 mapAll，再交出替换后的自身；
     * 带噪声的节点先 {@link Visitor#visitNoise} 再 {@link Visitor#apply}。
     */
    Density mapAll(Visitor visitor);

    interface Visitor {

        Density apply(Density input);

        default NoiseHolder visitNoise(NoiseHolder noise) {
            return noise;
        }
    }
}
