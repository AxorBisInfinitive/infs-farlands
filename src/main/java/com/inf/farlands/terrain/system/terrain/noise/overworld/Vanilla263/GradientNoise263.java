package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.RandomSource;

/**
 * 26.3 的梯度噪声基类，逐行对齐 {@code synth.GradientNoise}。
 *
 * <p>与 26.1.2 的差异有三处：置换表是 256 字节而不是 512 个 int，梯度点积在 float 上算，坐标折叠
 * 的开关是实例字段而不是静态调试常量。
 *
 * <p>随机流的消耗顺序是契约：三个 {@code nextDouble} 取偏移，随后 256 次 {@code nextInt} 洗置换表。
 * 少一次或者换顺序，其后所有八度都会取到别的随机值，也就是换地形。
 */
abstract class GradientNoise263 {

    /** 二十六边形的十六条整数梯度，后四条与前面前四条重合，与 26.3 的表逐位相同。 */
    private static final Gradient263[] GRADIENT = {
            new Gradient263(1, 1, 0),
            new Gradient263(-1, 1, 0),
            new Gradient263(1, -1, 0),
            new Gradient263(-1, -1, 0),
            new Gradient263(1, 0, 1),
            new Gradient263(-1, 0, 1),
            new Gradient263(1, 0, -1),
            new Gradient263(-1, 0, -1),
            new Gradient263(0, 1, 1),
            new Gradient263(0, -1, 1),
            new Gradient263(0, 1, -1),
            new Gradient263(0, -1, -1),
            new Gradient263(1, 1, 0),
            new Gradient263(0, -1, 1),
            new Gradient263(-1, 1, 0),
            new Gradient263(0, -1, -1)
    };

    /** 折叠半界，{@code Math.nextDown(1.6777216E7)}。左闭右开的下界就在这里。 */
    private static final double HALF_ROUND_OFF = Math.nextDown(1.6777216E7);

    /** 折叠周期。 */
    private static final double ROUND_OFF = 3.3554432E7;

    private final byte[] perms = new byte[256];
    final double offsetX;
    final double offsetY;
    final double offsetZ;

    /**
     * 是否关闭坐标折叠。
     *
     * <p>对应 26.3 的 {@code DEBUG_ENABLE_FARLANDS}，本移植体把它做成系统参数，默认开。开时
     * {@code wrap} 原样返回坐标，边境之地因此落在 32 位坐标边界上，而不是被折回。
     */
    final boolean enableFarlands;

    GradientNoise263(RandomSource random, boolean enableFarlands) {
        this.enableFarlands = enableFarlands;
        this.offsetX = random.nextDouble() * 256.0;
        this.offsetY = random.nextDouble() * 256.0;
        this.offsetZ = random.nextDouble() * 256.0;
        for (int i = 0; i < 256; i++) {
            this.perms[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++) {
            int offset = random.nextInt(256 - i);
            byte tmp = this.perms[i];
            this.perms[i] = this.perms[offset + i];
            this.perms[offset + i] = tmp;
        }
    }

    /** 取一个格点的八面体梯度。 */
    abstract float get(double x, double y, double z);

    int permute(int x) {
        return this.perms[x & 0xFF] & 0xFF;
    }

    Gradient263 permuteToGrad(int x) {
        return GRADIENT[this.permute(x) & 15];
    }

    static float gradDot(int hash, float x, float y, float z) {
        return GRADIENT[hash & 15].dot(x, y, z);
    }

    /**
     * 把坐标折回 int32 可表示范围。
     *
     * <p>区间左闭右开，上界用 {@code Math.nextDown} 取到 1.6777216E7 的前一个 double。这一位写错就
     * 会在边界上多折一次，表现为极远处出现一条接缝。
     */
    double wrap(double x) {
        if (this.enableFarlands) {
            return x;
        }
        return x >= -HALF_ROUND_OFF && x < HALF_ROUND_OFF ? x
                : x - Math.floor(x / ROUND_OFF + 0.5) * ROUND_OFF;
    }

    /** 一条梯度。double 与 float 两种点积并存，取哪一条决定精度，调用点不能随手挑。 */
    record Gradient263(int x, int y, int z) {

        double dot(double x, double y, double z) {
            return this.x * x + this.y * y + this.z * z;
        }

        float dot(float x, float y, float z) {
            return this.x * x + this.y * y + this.z * z;
        }
    }
}
