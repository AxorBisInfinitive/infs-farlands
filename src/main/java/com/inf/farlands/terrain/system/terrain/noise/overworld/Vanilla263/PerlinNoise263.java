package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 26.3 的 PerlinNoise 标量路径。
 *
 * <p>精度分界在相对坐标那一行：{@code wrap}、加偏移、{@code Mth.floor} 全在 double 上，而
 * {@code x - floorX} 之后的一切在 float 上。这一行挪动一位，出来的就是另一片地形。
 *
 * <p>批式路径 {@code addToVolume} 不移植，26.1.2 没有对应的缓冲接口，移植体逐方块调用。
 */
class PerlinNoise263 extends GradientNoise263 {

    PerlinNoise263(RandomSource random, boolean enableFarlands) {
        super(random, enableFarlands);
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
        float relativeY = (float) (y - floorY);
        float relativeZ = (float) (z - floorZ);
        return this.sampleAndLerp(floorX, floorY, floorZ, relativeX, relativeY, relativeZ, relativeY);
    }

    /**
     * 八个角点的梯度点积加三次插值。
     *
     * <p>{@code originalRelativeY} 与 {@code relativeY} 在 PerlinNoise 里是同一个值，分开传是为了让
     * 子类能只改参与点积的那一份而保留权重那一份，见 {@link SmearedPerlinNoise263}。
     */
    float sampleAndLerp(int x, int y, int z, float relativeX, float relativeY, float relativeZ,
            float originalRelativeY) {
        int x0 = this.permute(x);
        int x1 = this.permute(x + 1);
        int xy00 = this.permute(x0 + y);
        int xy01 = this.permute(x0 + y + 1);
        int xy10 = this.permute(x1 + y);
        int xy11 = this.permute(x1 + y + 1);
        float d000 = gradDot(this.permute(xy00 + z), relativeX, relativeY, relativeZ);
        float d100 = gradDot(this.permute(xy10 + z), relativeX - 1.0F, relativeY, relativeZ);
        float d010 = gradDot(this.permute(xy01 + z), relativeX, relativeY - 1.0F, relativeZ);
        float d110 = gradDot(this.permute(xy11 + z), relativeX - 1.0F, relativeY - 1.0F, relativeZ);
        float d001 = gradDot(this.permute(xy00 + z + 1), relativeX, relativeY, relativeZ - 1.0F);
        float d101 = gradDot(this.permute(xy10 + z + 1), relativeX - 1.0F, relativeY, relativeZ - 1.0F);
        float d011 = gradDot(this.permute(xy01 + z + 1), relativeX, relativeY - 1.0F, relativeZ - 1.0F);
        float d111 = gradDot(this.permute(xy11 + z + 1), relativeX - 1.0F, relativeY - 1.0F, relativeZ - 1.0F);
        float alphaX = Mth263.smoothstep(relativeX);
        float alphaY = Mth263.smoothstep(originalRelativeY);
        float alphaZ = Mth263.smoothstep(relativeZ);
        return Mth263.lerp3(alphaX, alphaY, alphaZ, d000, d100, d010, d110, d001, d101, d011, d111);
    }
}
