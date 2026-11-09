package com.inf.farlands.simulation.int64.noise.instance;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * vanilla {@code ImprovedNoise} 的逐字移植，一行未改。
 *
 * <p>格点是 {@code Mth.floor} 取的 int。那个转换在 {@code |x| ≥ 2^31} 时饱和，远处所有坐标因此落回同一条
 * 置换通道，地形按通道重复，这就是边境之地。本类保留它，不换成 long：换掉等于把这个模组要复现的地形删掉。
 *
 * <p>long 只出现在本层之外：{@code Density.compute} 的形参是 long，进本类之前一次性转 double。
 *
 * <p>角点下标 {@code p(x + 1)}、{@code p(x0 + y + 1)} 那几处也在 int 上做加法、也会回绕。这不改变取值：
 * {@code p} 只取 {@code x & 0xFF}，而 32 位回绕不改低八位。
 */
public final class ImprovedNoise {

    private final byte[] p;

    public final double xo;

    public final double yo;

    public final double zo;

    public ImprovedNoise(RandomSource random) {
        this.xo = random.nextDouble() * 256.0;
        this.yo = random.nextDouble() * 256.0;
        this.zo = random.nextDouble() * 256.0;
        this.p = new byte[256];
        for (int i = 0; i < 256; i++) {
            this.p[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++) {
            int offset = random.nextInt(256 - i);
            byte tmp = this.p[i];
            this.p[i] = this.p[i + offset];
            this.p[i + offset] = tmp;
        }
    }

    public double noise(double x, double y, double z) {
        return this.noise(x, y, z, 0.0, 0.0);
    }

    /**
     * 五参版本。vanilla 标它 {@code @Deprecated}，但 {@code PerlinNoise.getValue} 内部就走这一支，
     * 因此必须保留，行为也要一致。
     */
    public double noise(double startX, double startY, double startZ, double yScale, double yFudge) {
        double x = startX + this.xo;
        double y = startY + this.yo;
        double z = startZ + this.zo;
        int xf = Mth.floor(x);
        int yf = Mth.floor(y);
        int zf = Mth.floor(z);
        double xr = x - (double) xf;
        double yr = y - (double) yf;
        double zr = z - (double) zf;
        double yrFudge;
        if (yScale != 0.0) {
            double fudgeLimit;
            if (yFudge >= 0.0 && yFudge < yr) {
                fudgeLimit = yFudge;
            } else {
                fudgeLimit = yr;
            }
            yrFudge = Mth.floor(fudgeLimit / yScale + 1.0E-7F) * yScale;
        } else {
            yrFudge = 0.0;
        }
        return this.sampleAndLerp(xf, yf, zf, xr, yr - yrFudge, zr, yr);
    }

    private static double gradDot(int hash, double x, double y, double z) {
        return SimplexGradients.dot(SimplexGradients.GRADIENT[hash & 15], x, y, z);
    }

    private int p(int x) {
        return this.p[x & 0xFF] & 0xFF;
    }

    private double sampleAndLerp(int x, int y, int z, double xr, double yr, double zr, double yrOriginal) {
        int x0 = this.p(x);
        int x1 = this.p(x + 1);
        int xy00 = this.p(x0 + y);
        int xy01 = this.p(x0 + y + 1);
        int xy10 = this.p(x1 + y);
        int xy11 = this.p(x1 + y + 1);
        double d000 = gradDot(this.p(xy00 + z), xr, yr, zr);
        double d100 = gradDot(this.p(xy10 + z), xr - 1.0, yr, zr);
        double d010 = gradDot(this.p(xy01 + z), xr, yr - 1.0, zr);
        double d110 = gradDot(this.p(xy11 + z), xr - 1.0, yr - 1.0, zr);
        double d001 = gradDot(this.p(xy00 + z + 1), xr, yr, zr - 1.0);
        double d101 = gradDot(this.p(xy10 + z + 1), xr - 1.0, yr, zr - 1.0);
        double d011 = gradDot(this.p(xy01 + z + 1), xr, yr - 1.0, zr - 1.0);
        double d111 = gradDot(this.p(xy11 + z + 1), xr - 1.0, yr - 1.0, zr - 1.0);
        double xAlpha = Mth.smoothstep(xr);
        double yAlpha = Mth.smoothstep(yrOriginal);
        double zAlpha = Mth.smoothstep(zr);
        return Mth.lerp3(xAlpha, yAlpha, zAlpha, d000, d100, d010, d110, d001, d101, d011, d111);
    }
}
