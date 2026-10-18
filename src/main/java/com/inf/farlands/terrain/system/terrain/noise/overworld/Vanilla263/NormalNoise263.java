package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 26.3 的 NormalNoise，只承担两件事：把参数换算成八度与归一化因子，再按随机流造出噪声实例。
 *
 * <p>26.1.2 的 NormalNoise 自己就是采样器，26.3 把它拆成参数持有加工厂，采样交给 NoiseStack。
 * 移植体照 26.3 的形状。
 *
 * <p>归一化有两层：先按 {@code createParity} 复刻旧版振幅基准，再按标准差把和压回目标偏差。
 * 两层都依赖 {@code PerlinNoise} 的标准差 {@code 0.2702247831245211}，改一个数字整条噪声的幅度就变。
 */
final class NormalNoise263 {

    /** 单个 PerlinNoise 的标准差，26.3 里是 PerlinNoise.STANDARD_DEVIATION。 */
    private static final double OCTAVE_DEVIATION = 0.2702247831245211;

    /** 目标偏差。 */
    private static final double TARGET_DEVIATION = 0.3333333333333333;

    /** 第二组八度的频率倍数，用来打散两组采样的一致性。 */
    private static final double SECOND_FREQUENCY_FACTOR = 1.0181268882175227;

    private static final double PERSISTENCE = 0.5;

    private final List<Octave263> octaves;
    private final double normalizationFactor;

    private NormalNoise263(int firstOctave, double[] amplitudes) {
        double baseAmplitude = computeParityBaseAmplitude(firstOctave, amplitudes);
        this.octaves = buildOctaves(firstOctave, baseAmplitude, amplitudes);
        double targetAmplitude = 0.0;
        for (Octave263 octave : this.octaves) {
            targetAmplitude += Math.abs(octave.amplitude());
        }
        this.normalizationFactor = computeNormalizationFactor(targetAmplitude, this.octaves);
    }

    /** 按 {@code createParity} 的参数形态造一个噪声定义。 */
    static NormalNoise263 parity(int firstOctave, double... amplitudes) {
        if (amplitudes.length == 0) {
            throw new IllegalArgumentException("farlands: need at least one amplitude");
        }
        return new NormalNoise263(firstOctave, amplitudes);
    }

    /**
     * 用给定的随机流造出采样器。
     *
     * <p>每个八度造两条 PerlinNoise，分别来自两条独立 fork 的随机流，第二条频率乘一个固定倍数。
     * 两条流必须按八度顺序依次取哈希，取反了就是两组错位的噪声。
     */
    NoiseStack263 create(RandomSource random, boolean enableFarlands) {
        PositionalRandomFactory first = random.forkPositional();
        PositionalRandomFactory second = random.forkPositional();
        List<NoiseStack263.Layer263> layers = new ArrayList<>(this.octaves.size() * 2);
        for (Octave263 octave : this.octaves) {
            String seed = octave.seed();
            float valueFactor = (float) (this.normalizationFactor * octave.amplitude());
            layers.add(new NoiseStack263.Layer263(
                    new PerlinNoise263(first.fromHashOf(seed), enableFarlands), octave.frequency(), valueFactor));
            layers.add(new NoiseStack263.Layer263(
                    new PerlinNoise263(second.fromHashOf(seed), enableFarlands),
                    octave.frequency() * SECOND_FREQUENCY_FACTOR, valueFactor));
        }
        return new NoiseStack263(layers.toArray(new NoiseStack263.Layer263[0]));
    }

    private static List<Octave263> buildOctaves(int baseOctave, double baseAmplitude, double[] amplitudes) {
        double frequency = Math.pow(2.0, baseOctave);
        // 归一化因子先算成一个数再乘。写成 baseAmplitude * A / B 会在浮点上与 A / B 再乘差最后一位，
        // 而这一位会沿着所有八度放大成整条噪声的偏差。
        double normalization = Math.pow(PERSISTENCE, -(amplitudes.length - 1))
                / (Math.pow(PERSISTENCE, -amplitudes.length) - 1.0);
        double amplitude = baseAmplitude * normalization;
        List<Octave263> built = new ArrayList<>(amplitudes.length);
        for (int i = 0; i < amplitudes.length; i++) {
            double modifier = amplitudes[i];
            if (modifier != 0.0) {
                built.add(new Octave263(baseOctave + i, frequency, amplitude * modifier));
            }
            frequency *= 2.0;
            amplitude *= PERSISTENCE;
        }
        return built;
    }

    private static double computeNormalizationFactor(double targetAmplitude, List<Octave263> octaves) {
        double inputDeviation = estimateDeviation(octaves);
        if (inputDeviation == 0.0) {
            return 0.0;
        }
        return targetAmplitude * TARGET_DEVIATION / (inputDeviation * Math.sqrt(2.0));
    }

    private static double estimateDeviation(List<Octave263> octaves) {
        double variance = 0.0;
        for (Octave263 octave : octaves) {
            double layerDeviation = OCTAVE_DEVIATION * Math.abs(octave.amplitude());
            variance += layerDeviation * layerDeviation;
        }
        return Math.sqrt(variance);
    }

    /**
     * 复刻旧版的振幅基准。
     *
     * <p>旧版把第一条振幅单独传进来，26.3 改成从振幅表反推。反推用的是旧版归一化因子与新因子的比值，
     * 所以同一组振幅在新旧两版得到的绝对值不同，只有走这条比值才能对上。
     */
    private static double computeParityBaseAmplitude(int baseOctave, double[] amplitudes) {
        List<Octave263> octaves = buildOctaves(baseOctave, 1.0, amplitudes);
        double targetAmplitude = 0.0;
        for (Octave263 octave : octaves) {
            targetAmplitude += Math.abs(octave.amplitude());
        }
        double newFactor = computeNormalizationFactor(targetAmplitude, octaves);
        if (newFactor == 0.0) {
            return 1.0;
        }
        return computeParityNormalizationFactor(1.0, amplitudes) / newFactor;
    }

    private static double computeParityNormalizationFactor(double baseAmplitude, double[] amplitudes) {
        int minOctave = Integer.MAX_VALUE;
        int maxOctave = Integer.MIN_VALUE;
        for (int i = 0; i < amplitudes.length; i++) {
            if (amplitudes[i] != 0.0) {
                minOctave = Math.min(minOctave, i);
                maxOctave = Math.max(maxOctave, i);
            }
        }
        return baseAmplitude * 0.5 * TARGET_DEVIATION / parityExpectedDeviation(maxOctave - minOctave);
    }

    private static double parityExpectedDeviation(int octaveSpan) {
        return 0.1 * (1.0 + 1.0 / (octaveSpan + 1));
    }

    /** 一个八度。种子名是 26.3 定死的格式，换写法就是换噪声。 */
    private record Octave263(int index, double frequency, double amplitude) {

        String seed() {
            return "octave_" + this.index;
        }
    }
}
