package com.inf.farlands.terrain.system.common.overworld.TheBeneath;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 三轴位移的噪声节点，逐字取自 26.1.2 的 {@code DensityFunctions.ShiftedNoise}。
 *
 * <p>复制的原因是公开工厂 {@code DensityFunctions.shiftedNoise2d} 把 {@code shift_y} 固定成 0、
 * {@code y_scale} 固定成 0.0，而虚空之下的 b 节点要非零的垂直位移与 1.7 的容差，那两处正是地形横向
 * 错动的来源。
 *
 * <p>三个 shift 都是密度函数而不是常数，与 vanilla 一致：位移量本身可以由别的噪声给出。
 */
public record TheBeneathShiftedNoise(DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ,
        double xzScale, double yScale, DensityFunction.NoiseHolder noise) implements DensityFunction {

    public static DensityFunction of(DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ,
            double xzScale, double yScale, DensityFunction.NoiseHolder noise) {
        return new TheBeneathShiftedNoise(shiftX, shiftY, shiftZ, xzScale, yScale, noise);
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double x = context.blockX() * this.xzScale + this.shiftX.compute(context);
        double y = context.blockY() * this.yScale + this.shiftY.compute(context);
        double z = context.blockZ() * this.xzScale + this.shiftZ.compute(context);
        return this.noise.getValue(x, y, z);
    }

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new TheBeneathShiftedNoise(
                this.shiftX.mapAll(visitor),
                this.shiftY.mapAll(visitor),
                this.shiftZ.mapAll(visitor),
                this.xzScale,
                this.yScale,
                visitor.visitNoise(this.noise)));
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
