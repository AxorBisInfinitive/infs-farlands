package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.Density;

/**
 * 常量折叠后的加减乘。vanilla 的 {@code DensityFunctions.MulOrAdd}。
 *
 * <p>它存在的唯一理由是性能与形状：常量一侧被抽出来，{@code compute} 只剩一次乘或加，且没有
 * {@link TwoArgument} 乘法那条「左侧为零直接给 0」的短路。两者在常量取 {@code Infinity} 或 {@code NaN}
 * 且另一侧为零时不同值，这是 {@link TwoArgument#create} 必须按 vanilla 折叠的原因。
 *
 * <p>{@code mapAll} 不调 {@code visitor.apply}，与 vanilla 的 {@code PureTransformer} 一致。
 */
public record MulOrAdd(Type specificType, Density input, double minValue, double maxValue, double argument)
        implements Density {

    public enum Type {
        MUL,
        ADD
    }

    @Override
    public double compute(long x, long y, long z) {
        double v = this.input.compute(x, y, z);
        return this.specificType == Type.MUL ? v * this.argument : v + this.argument;
    }

    @Override
    public Density mapAll(Visitor visitor) {
        Density mapped = this.input.mapAll(visitor);
        double low = mapped.minValue();
        double high = mapped.maxValue();
        double min;
        double max;
        if (this.specificType == Type.ADD) {
            min = low + this.argument;
            max = high + this.argument;
        } else if (this.argument >= 0.0) {
            min = low * this.argument;
            max = high * this.argument;
        } else {
            min = high * this.argument;
            max = low * this.argument;
        }
        return new MulOrAdd(this.specificType, mapped, min, max, this.argument);
    }
}
