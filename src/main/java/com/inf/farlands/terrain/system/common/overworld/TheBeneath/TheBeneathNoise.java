package com.inf.farlands.terrain.system.common.overworld.TheBeneath;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 噪声节点，逐字取自 26.1.2 的 {@code DensityFunctions.Noise}。
 *
 * <p>复制的原因是公开工厂 {@code DensityFunctions.noise(Holder, double, double)} 只构造
 * {@code new NoiseHolder(holder)}，即 noise 为 null，而 {@code NoiseHolder.getValue} 在 noise 为 null 时
 * 恒返回 0.0。用工厂建出来的节点是一个不报错的常数零，地形会退化成全空气或全实心。所以构造器收一个
 * 已接好 {@code NormalNoise} 的 holder。
 *
 * <p>{@code xzScale} 在 26.1.2 带 {@code @Deprecated} 但仍在 compute 里参与乘法，照抄保留，本副本不带
 * 那个注解，只为不引编译告警。
 */
public record TheBeneathNoise(DensityFunction.NoiseHolder noise, double xzScale, double yScale)
        implements DensityFunction {

    public static DensityFunction of(DensityFunction.NoiseHolder noise, double xzScale, double yScale) {
        return new TheBeneathNoise(noise, xzScale, yScale);
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return this.noise.getValue(
                context.blockX() * this.xzScale,
                context.blockY() * this.yScale,
                context.blockZ() * this.xzScale);
    }

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new TheBeneathNoise(visitor.visitNoise(this.noise), this.xzScale, this.yScale));
    }

    @Override
    public double minValue() {
        return -this.maxValue();
    }

    @Override
    public double maxValue() {
        return this.noise.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
