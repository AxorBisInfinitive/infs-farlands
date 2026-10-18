package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 26.3 的 SmearedPerlinNoise，只被 BlendedNoise 的三组八度用到。
 *
 * <p>它把 y 的相对量按 {@code fudgeYScale} 做一次量化，再拿量化后的值参与点积，而插值权重仍用未
 * 量化的那一份。这就是 smeared 的含义：竖直方向被故意抹平，混合噪声因此不产生竖直条纹。
 *
 * <p>{@code relativeY} 在 26.3 里是 double，不是 float。改成 float 会先丢精度再量化，量化边界附近
 * 的结果会翻到另一档。
 */
class SmearedPerlinNoise263 extends PerlinNoise263 {

    private final double fudgeYScale;

    SmearedPerlinNoise263(RandomSource random, double fudgeYScale, boolean enableFarlands) {
        super(random, enableFarlands);
        this.fudgeYScale = fudgeYScale;
    }

    @Override
    float get(double rawX, double rawY, double rawZ) {
        double x = this.wrap(rawX) + this.offsetX;
        double y = this.wrap(rawY) + this.offsetY;
        double z = this.wrap(rawZ) + this.offsetZ;
        int floorX = Mth.floor(x);
        int floorY = Mth.floor(y);
        int floorZ = Mth.floor(z);
        float relativeX = (float) (x - floorX);
        double relativeY = y - floorY;
        float relativeZ = (float) (z - floorZ);
        float fudgedRelativeY = (float) (relativeY - this.computeFudgeY(rawY, relativeY));
        return this.sampleAndLerp(floorX, floorY, floorZ, relativeX, fudgedRelativeY, relativeZ,
                (float) relativeY);
    }

    /**
     * 把 y 的相对量向下量化到 {@code fudgeYScale} 的整数倍。
     *
     * <p>取的是原始参数 {@code rawY} 而不是加过偏移的坐标，与 26.3 一致。加 {@code 1.0E-7F} 是为了
     * 让量化在边界上偏向上侧，少了这一位，整个 y 轴会平移到另一档。
     */
    private double computeFudgeY(double rawY, double relativeY) {
        double fudgeLimit = rawY >= 0.0 && rawY < relativeY ? rawY : relativeY;
        return Mth.floor(fudgeLimit / this.fudgeYScale + 1.0E-7F) * this.fudgeYScale;
    }
}
