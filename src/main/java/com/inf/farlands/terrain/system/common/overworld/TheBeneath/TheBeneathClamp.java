package com.inf.farlands.terrain.system.common.overworld.TheBeneath;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 钳制节点，逐字取自 26.1.2 的 {@code DensityFunctions.Clamp}。
 *
 * <p>复制的原因同样是缺公开工厂：{@code DensityFunctions.Clamp} 的构造器是 protected，
 * {@code DensityFunctions} 里也没有 clamp 工厂。
 *
 * <p>记录的 {@code minValue} 与 {@code maxValue} 两个分量本身就是密度函数的同名访问器，即钳制的两端同时
 * 是本节点的值域声明，与 vanilla 一致。
 */
public record TheBeneathClamp(DensityFunction input, double minValue, double maxValue)
        implements TheBeneathPureTransformer {

    public static DensityFunction of(DensityFunction input, double minValue, double maxValue) {
        return new TheBeneathClamp(input, minValue, maxValue);
    }

    @Override
    public double transform(double input) {
        return Mth.clamp(input, this.minValue, this.maxValue);
    }

    /** 只下钻输入，与 vanilla 同名方法逐字一致。 */
    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return new TheBeneathClamp(this.input.mapAll(visitor), this.minValue, this.maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
