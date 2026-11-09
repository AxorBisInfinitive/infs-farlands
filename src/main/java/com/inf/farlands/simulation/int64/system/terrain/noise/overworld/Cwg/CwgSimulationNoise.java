package com.inf.farlands.simulation.int64.system.terrain.noise.overworld.Cwg;

import java.util.Random;

/**
 * 逐阶 Perlin，地形层 CwgNoise 的复制体。
 *
 * <p>除包名、类名与本注释之外，类体一个字未改。穹顶的成因就在下面三处语义里，复制时原样搬过来：
 *
 * <ul>
 * <li>坐标转 int 是饱和而不是回绕，于是 |F| 越过 2^31 之后格点索引冻结。</li>
 * <li>第二个格点是第一个加一，加法按 int32 回绕：从 2147483647 绕到 -2147483648，两者之差是 2^32-1。</li>
 * <li>梯度索引由三个格点索引与种子做 int32 线性组合、再异或右移取低八位，索引冻结后梯度即为常数。</li>
 * </ul>
 *
 * <p>三条合起来的结果是：插值权重离开 [0, 1]，不再构成插值，混合式的零集退化成一张平面，也就是穹顶。
 *
 * <p>foldCoordinates 为假时不折叠，坐标可以一路往 int64 域外走，穹顶在 2^31 的索引上界处如常形成。
 * 为真时坐标被折回 int32 范围，饱和永不发生，穹顶消失。
 *
 * <p>复制而不是复用，是为了让模拟层不依赖地形层的包；一致的代价是两条链后续可能分叉，而编译不会报，
 * 只能靠比对发现。
 */
public final class CwgSimulationNoise {

    /** 每阶振幅衰减与频率倍增，与库实现的默认值一致。 */
    private static final double PERSISTENCE = 0.5;
    private static final double LACUNARITY = 2.0;

    /** 折叠阈值与周期，取格点的 int32 边界的一半。 */
    private static final double FOLD_LIMIT = 1073741824.0;

    private static final int X_NOISE_GEN = 1619;
    private static final int Y_NOISE_GEN = 31337;
    private static final int Z_NOISE_GEN = 6971;
    private static final int SEED_NOISE_GEN = 1013;
    private static final int SHIFT_NOISE_GEN = 8;

    /** 256 行梯度表，每行 4 个分量，只用前三个。构造后只读。 */
    private static final double[] GRADIENTS = new double[256 * 4];

    static {
        // vanilla 的整数梯度分量除以 2。三张表按 1.12.2 NoiseGeneratorImproved 的 GRAD_X、GRAD_Y、
        // GRAD_Z 逐位照抄：这些位会经抽样进入 256 行梯度表，任何一位按对称性补齐都会改地形。
        // 逐行的抽样顺序固定，必须与建表时消费同一随机流的那一步一致，否则每行拿到的梯度会整体错位。
        int[] gradX = { 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0 };
        int[] gradY = { 1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1 };
        int[] gradZ = { 0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1 };
        Random random = new Random(123456789);
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(gradX.length);
            GRADIENTS[i * 4] = gradX[j] / 2.0;
            GRADIENTS[i * 4 + 1] = gradY[j] / 2.0;
            GRADIENTS[i * 4 + 2] = gradZ[j] / 2.0;
        }
    }

    private final double frequencyX;

    private final double frequencyY;

    private final double frequencyZ;

    private final int octaves;

    private final int seed;

    private final boolean foldCoordinates;

    private final double normalization;

    public CwgSimulationNoise(double frequencyX, double frequencyY, double frequencyZ, int octaves, int seed,
            boolean foldCoordinates) {
        this.frequencyX = frequencyX;
        this.frequencyY = frequencyY;
        this.frequencyZ = frequencyZ;
        this.octaves = octaves;
        this.seed = seed;
        this.foldCoordinates = foldCoordinates;
        this.normalization = 2.0 / maxValue(octaves);
    }

    /**
     * 长 seed 折叠成库接收的 int seed。直接强转与先折叠再取低 32 位不是同一个数，长 seed 必须
     * 走这里，否则四路噪声拿到的都不是期望的那个种子。
     */
    public static int foldSeed(long seed) {
        return (int) ((seed & 0xFFFFFFFFL) ^ (seed >>> 32));
    }

    /** 归一化到 [-1,1] 的取值，等价于库实现的频率缩放加一次归一化。 */
    public double normalized(double x, double y, double z) {
        return sample(x, y, z) * this.normalization - 1.0;
    }

    /** 全部八度之和，未归一化。 */
    private double sample(double x, double y, double z) {
        double x1 = x * this.frequencyX;
        double y1 = y * this.frequencyY;
        double z1 = z * this.frequencyZ;
        double value = 0.0;
        double persistence = 1.0;
        for (int octave = 0; octave < this.octaves; octave++) {
            double nx = this.foldCoordinates ? fold(x1) : x1;
            double ny = this.foldCoordinates ? fold(y1) : y1;
            double nz = this.foldCoordinates ? fold(z1) : z1;
            value += coherent(nx, ny, nz, this.seed + octave) * persistence;
            x1 *= LACUNARITY;
            y1 *= LACUNARITY;
            z1 *= LACUNARITY;
            persistence *= PERSISTENCE;
        }
        return value;
    }

    /** 单阶的八度最大值，用于把和压回 [-1,1]。 */
    private static double maxValue(int octaves) {
        return (Math.pow(PERSISTENCE, octaves) - 1.0) / (PERSISTENCE - 1.0);
    }

    /** 把坐标折回 int32 范围，保证后面的整数转换不会越界。 */
    private static double fold(double n) {
        if (n >= FOLD_LIMIT) {
            return (2.0 * n % FOLD_LIMIT) - FOLD_LIMIT;
        }
        if (n <= -FOLD_LIMIT) {
            return (2.0 * n % FOLD_LIMIT) + FOLD_LIMIT;
        }
        return n;
    }

    /** 八点三线性插值，权重走三次 S 曲线。 */
    private static double coherent(double x, double y, double z, int seed) {
        int x0 = x > 0.0 ? (int) x : (int) x - 1;
        int y0 = y > 0.0 ? (int) y : (int) y - 1;
        int z0 = z > 0.0 ? (int) z : (int) z - 1;
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        double xs = sCurve3(x - x0);
        double ys = sCurve3(y - y0);
        double zs = sCurve3(z - z0);

        double n0 = gradient(x, y, z, x0, y0, z0, seed);
        double n1 = gradient(x, y, z, x1, y0, z0, seed);
        double ix0 = lerp(n0, n1, xs);
        n0 = gradient(x, y, z, x0, y1, z0, seed);
        n1 = gradient(x, y, z, x1, y1, z0, seed);
        double ix1 = lerp(n0, n1, xs);
        double iy0 = lerp(ix0, ix1, ys);
        n0 = gradient(x, y, z, x0, y0, z1, seed);
        n1 = gradient(x, y, z, x1, y0, z1, seed);
        ix0 = lerp(n0, n1, xs);
        n0 = gradient(x, y, z, x0, y1, z1, seed);
        n1 = gradient(x, y, z, x1, y1, z1, seed);
        ix1 = lerp(n0, n1, xs);
        double iy1 = lerp(ix0, ix1, ys);
        return lerp(iy0, iy1, zs);
    }

    /** 单个角点的梯度点积。线性组合按 int32 回绕，右移是算术右移。 */
    private static double gradient(double fx, double fy, double fz, int ix, int iy, int iz, int seed) {
        int index = X_NOISE_GEN * ix + Y_NOISE_GEN * iy + Z_NOISE_GEN * iz + SEED_NOISE_GEN * seed;
        index ^= index >> SHIFT_NOISE_GEN;
        index &= 0xFF;
        return GRADIENTS[index * 4] * (fx - ix)
                + GRADIENTS[index * 4 + 1] * (fy - iy)
                + GRADIENTS[index * 4 + 2] * (fz - iz)
                + 0.5;
    }

    private static double sCurve3(double a) {
        return a * a * (3.0 - 2.0 * a);
    }

    private static double lerp(double a, double b, double t) {
        return (1.0 - t) * a + t * b;
    }
}
