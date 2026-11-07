package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.Density;

/**
 * 二元组合，四种运算。vanilla 的 {@code DensityFunctions.Ap2}。
 *
 * <p>三处必须照抄，改了会静默改值：
 *
 * <ul>
 * <li>乘法在左侧为零时直接给 0，不去算右侧。</li>
 * <li>取小与取大各有一次界短路：左侧已经小于右侧的下界时直接返回左侧，不算右侧。</li>
 * <li>{@link #create} 的界公式与折叠规则。乘法那个界不是四个乘积取最小最大，而是按两侧的符号分三支，
 * 见 vanilla 的 {@code TwoArgumentSimpleFunction.create}。</li>
 * </ul>
 *
 * <p>{@link #create} 在一侧是 {@link Constant} 且运算是加或乘时返回 {@link MulOrAdd}，与 vanilla 同式。
 * 折叠出来的节点没有乘法那条零短路，所以折叠不是纯粹的形状优化，它决定常量取 {@code Infinity} 或
 * {@code NaN} 时的值。
 */
public record TwoArgument(Type type, Density argument1, Density argument2, double minValue, double maxValue)
        implements Density {

    public enum Type {
        ADD,
        MUL,
        MIN,
        MAX
    }

    public static Density create(Type type, Density argument1, Density argument2) {
        double min1 = argument1.minValue();
        double min2 = argument2.minValue();
        double max1 = argument1.maxValue();
        double max2 = argument2.maxValue();
        double min;
        double max;
        switch (type) {
            case ADD -> {
                min = min1 + min2;
                max = max1 + max2;
            }
            case MUL -> {
                min = min1 > 0.0 && min2 > 0.0 ? min1 * min2
                        : (max1 < 0.0 && max2 < 0.0 ? max1 * max2 : Math.min(min1 * max2, max1 * min2));
                max = min1 > 0.0 && min2 > 0.0 ? max1 * max2
                        : (max1 < 0.0 && max2 < 0.0 ? min1 * min2 : Math.max(min1 * min2, max1 * max2));
            }
            case MIN -> {
                min = Math.min(min1, min2);
                max = Math.min(max1, max2);
            }
            default -> {
                min = Math.max(min1, min2);
                max = Math.max(max1, max2);
            }
        }
        if ((type == Type.MUL || type == Type.ADD) && argument1 instanceof Constant constant) {
            return new MulOrAdd(type == Type.ADD ? MulOrAdd.Type.ADD : MulOrAdd.Type.MUL, argument2, min, max,
                    constant.value());
        }
        if ((type == Type.MUL || type == Type.ADD) && argument2 instanceof Constant constant) {
            return new MulOrAdd(type == Type.ADD ? MulOrAdd.Type.ADD : MulOrAdd.Type.MUL, argument1, min, max,
                    constant.value());
        }
        return new TwoArgument(type, argument1, argument2, min, max);
    }

    @Override
    public double compute(long x, long y, long z) {
        double v1 = this.argument1.compute(x, y, z);
        return switch (this.type) {
            case ADD -> v1 + this.argument2.compute(x, y, z);
            case MUL -> v1 == 0.0 ? 0.0 : v1 * this.argument2.compute(x, y, z);
            case MIN -> v1 < this.argument2.minValue() ? v1
                    : Math.min(v1, this.argument2.compute(x, y, z));
            case MAX -> v1 > this.argument2.maxValue() ? v1
                    : Math.max(v1, this.argument2.compute(x, y, z));
        };
    }

    @Override
    public Density mapAll(Visitor visitor) {
        return visitor.apply(create(this.type, this.argument1.mapAll(visitor), this.argument2.mapAll(visitor)));
    }
}
