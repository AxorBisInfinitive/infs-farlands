package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 移植体所有密度节点的基类。
 *
 * <p>26.3 的节点在 float 缓冲上取值，节点之间传的是 float。26.1.2 的面是
 * {@code compute(FunctionContext)} 返回 double，所以每个节点只实现 {@link #computeF}，
 * 由本类在出口做一次拓宽。
 *
 * <p>子类的 {@code computeF} 里不许出现 double 中间量。写错一处，值不会报错，只会和 26.3 差末几位。
 */
abstract class FloatNode263 implements DensityFunction.SimpleFunction {

    @Override
    public final double compute(DensityFunction.FunctionContext context) {
        return this.computeF(context);
    }

    /** 该节点在给定坐标上的 float 取值。 */
    abstract float computeF(DensityFunction.FunctionContext context);

    /**
     * 取值下界。
     *
     * <p>26.3 用区间算术算这个值，移植体没有 {@code Interval}，一律给宽界。它只服务区间推导与
     * 校验，不参与取值；给宽界只会让上层的优化更保守，不会改变结果。
     */
    @Override
    public double minValue() {
        return -1.0E30;
    }

    @Override
    public double maxValue() {
        return 1.0E30;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
