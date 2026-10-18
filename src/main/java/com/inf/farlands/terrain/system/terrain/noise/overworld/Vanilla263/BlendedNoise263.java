package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 26.3 的 BlendedNoise 值路径。
 *
 * <p>26.3 把它改成 record 加采样器组合，取值由 {@code NoiseFunction.Sampler}、{@code ConstAddSampler}、
 * {@code ClampFunction}、{@code LerpFunction} 依次算出。这里把那条组合展开成一次调用，算式见
 * {@link #sample}。
 *
 * <p>三个八度栈必须按 min、max、main 的顺序用同一个随机流造出来。顺序换了就是三组互换的噪声，
 * 而地形看上去仍然像地形，不会报错。
 */
final class BlendedNoise263 {

    private static final double BASE_SCALE = 684.412;

    /** 上下界两条 fbm 的振幅因子，26.3 写作 0.99998474F，传参时拓宽成 double。 */
    private static final float LIMIT_VALUE_FACTOR = 0.99998474F;

    /** 主噪声的振幅因子。 */
    private static final double MAIN_VALUE_FACTOR = 12.75;

    private static final int LIMIT_FIRST_OCTAVE = -15;
    private static final int MAIN_FIRST_OCTAVE = -7;

    private final NoiseStack263 minLimit;
    private final NoiseStack263 maxLimit;
    private final NoiseStack263 main;
    private final double xzMultiplier;
    private final double yMultiplier;
    private final double mainXzMultiplier;
    private final double mainYMultiplier;

    BlendedNoise263(double xzScale, double yScale, double xzFactor, double yFactor,
            double smearScaleMultiplier, RandomSource random, boolean enableFarlands) {
        this.xzMultiplier = BASE_SCALE * xzScale;
        this.yMultiplier = BASE_SCALE * yScale;
        this.mainXzMultiplier = this.xzMultiplier / xzFactor;
        this.mainYMultiplier = this.yMultiplier / yFactor;
        double limitSmearScaleY = this.yMultiplier * smearScaleMultiplier;
        double mainSmearScaleY = limitSmearScaleY / yFactor;
        this.minLimit = createFbm(random, LIMIT_FIRST_OCTAVE, limitSmearScaleY, LIMIT_VALUE_FACTOR,
                enableFarlands);
        this.maxLimit = createFbm(random, LIMIT_FIRST_OCTAVE, limitSmearScaleY, LIMIT_VALUE_FACTOR,
                enableFarlands);
        this.main = createFbm(random, MAIN_FIRST_OCTAVE, mainSmearScaleY, MAIN_VALUE_FACTOR, enableFarlands);
    }

    /**
     * 主世界默认预设。
     *
     * <p>五个参数取自 {@code data/minecraft/worldgen/density_function/overworld/base_3d_noise.json}，
     * 26.1.2 与 26.3 的那份文件逐字相同，所以这里直接落常量，不必读数据包。
     */
    static BlendedNoise263 overworld(RandomSource random, boolean enableFarlands) {
        return new BlendedNoise263(0.25, 0.125, 80.0, 160.0, 8.0, random, enableFarlands);
    }

    /**
     * 26.3 的混合噪声取值。
     *
     * <p>四个坐标乘法在 double 上，进 {@code get} 之后全在 float 上，出 float。上层用
     * {@code clamp(main + 0.5, 0, 1)} 当插值权重，且在权重恰为 0 或 1 时直接返回那一侧，不走插值。
     * 那两个特判不能省成一句 lerp。
     */
    float sample(double x, double y, double z) {
        float choice = Mth.clamp(
                this.main.get(x * this.mainXzMultiplier, y * this.mainYMultiplier, z * this.mainXzMultiplier)
                        + 0.5F,
                0.0F, 1.0F);
        float low = this.minLimit.get(x * this.xzMultiplier, y * this.yMultiplier, z * this.xzMultiplier);
        float high = this.maxLimit.get(x * this.xzMultiplier, y * this.yMultiplier, z * this.xzMultiplier);
        if (choice == 0.0F) {
            return low;
        }
        if (choice == 1.0F) {
            return high;
        }
        return Mth.lerp(choice, low, high);
    }

    /**
     * 造一条 fbm。
     *
     * <p>振幅按八度数归一，随后每层频率减半、振幅加倍。逐层构造 {@link SmearedPerlinNoise263} 会依次
     * 消耗随机流，所以层序就是随机序。
     */
    private static NoiseStack263 createFbm(RandomSource random, int firstOctave, double smearScaleY,
            double valueFactor, boolean enableFarlands) {
        int octaves = -firstOctave + 1;
        double factor = 1.0;
        valueFactor /= Math.pow(2.0, octaves) - 1.0;
        NoiseStack263.Layer263[] layers = new NoiseStack263.Layer263[octaves];
        int index = 0;
        for (int i = octaves - 1; i >= 0; i--) {
            layers[index++] = new NoiseStack263.Layer263(
                    new SmearedPerlinNoise263(random, smearScaleY * factor, enableFarlands), factor,
                    (float) valueFactor);
            factor /= 2.0;
            valueFactor *= 2.0;
        }
        return new NoiseStack263(layers);
    }
}
