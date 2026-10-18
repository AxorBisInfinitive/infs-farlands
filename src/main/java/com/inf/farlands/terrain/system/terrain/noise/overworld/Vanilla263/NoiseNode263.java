package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 噪声叶节点，对应 26.3 的 {@code NoiseFunction} 三种采样器形态。
 *
 * <p>坐标乘法在 double 上，随后进噪声。本链只有两种用法：无位移的，以及只带水平两个位移的，
 * 对应 {@code shiftedNoise2d}。三种形态共用一个类，位移为空即走无位移那一支。
 */
final class NoiseNode263 extends FloatNode263 {

    private final NoiseStack263 noise;
    private final double xzScale;
    private final double yScale;
    private final FloatNode263 shiftX;
    private final FloatNode263 shiftY;
    private final FloatNode263 shiftZ;

    private NoiseNode263(NoiseStack263 noise, double xzScale, double yScale, FloatNode263 shiftX,
            FloatNode263 shiftY, FloatNode263 shiftZ) {
        this.noise = noise;
        this.xzScale = xzScale;
        this.yScale = yScale;
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        this.shiftZ = shiftZ;
    }

    /** 无位移，对应 26.3 的 {@code NoiseFunction.Sampler}。 */
    static NoiseNode263 plain(NoiseStack263 noise, double xzScale, double yScale) {
        return new NoiseNode263(noise, xzScale, yScale, null, null, null);
    }

    /** 只有水平位移，对应 26.3 的 {@code ShiftedXzSampler}。 */
    static NoiseNode263 shiftedXz(NoiseStack263 noise, double xzScale, double yScale, FloatNode263 shiftX,
            FloatNode263 shiftZ) {
        return new NoiseNode263(noise, xzScale, yScale, shiftX, null, shiftZ);
    }

    /** 三轴位移，对应 26.3 的 {@code ShiftedXyzSampler}。 */
    static NoiseNode263 shiftedXyz(NoiseStack263 noise, double xzScale, double yScale, FloatNode263 shiftX,
            FloatNode263 shiftY, FloatNode263 shiftZ) {
        return new NoiseNode263(noise, xzScale, yScale, shiftX, shiftY, shiftZ);
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        if (this.shiftX == null) {
            return this.noise.getScaled(context.blockX(), context.blockY(), context.blockZ(), this.xzScale,
                    this.yScale);
        }
        double x = context.blockX() * this.xzScale + this.shiftX.computeF(context);
        double y = this.shiftY == null ? context.blockY() * this.yScale
                : context.blockY() * this.yScale + this.shiftY.computeF(context);
        double z = context.blockZ() * this.xzScale + this.shiftZ.computeF(context);
        return this.noise.get(x, y, z);
    }
}
