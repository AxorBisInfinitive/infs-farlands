package com.inf.farlands.simulation.int64.noise.instance;

import com.inf.farlands.simulation.int64.noise.Noise;
import com.inf.farlands.simulation.int64.noise.NoiseParameters;

import net.minecraft.util.RandomSource;

/**
 * vanilla {@code NormalNoise} 的逐字移植。
 *
 * <p>两条 {@code PerlinNoise} 从同一个随机流依次取出，顺序与 vanilla 相同：先取 first，再取 second。
 * 顺序反了，两条噪声互换，地形随之改变，而这个错编译期看不出来。
 *
 * <p>{@code valueFactor} 由非零幅度的首末八度跨度算出，与 vanilla 同式。
 */
public final class NormalNoise implements Noise {

    private static final double INPUT_FACTOR = 1.0181268882175227;

    private final double valueFactor;

    private final PerlinNoise first;

    private final PerlinNoise second;

    private final double maxValue;

    private final NoiseParameters parameters;

    public static NormalNoise create(RandomSource random, NoiseParameters parameters) {
        return new NormalNoise(random, parameters);
    }

    private NormalNoise(RandomSource random, NoiseParameters parameters) {
        this.parameters = parameters;
        double[] amplitudes = new double[parameters.amplitudes().size()];
        for (int i = 0; i < amplitudes.length; i++) {
            amplitudes[i] = parameters.amplitudes().get(i);
        }
        this.first = PerlinNoise.create(random, parameters.firstOctave(), amplitudes);
        this.second = PerlinNoise.create(random, parameters.firstOctave(), amplitudes);
        int minOctave = Integer.MAX_VALUE;
        int maxOctave = Integer.MIN_VALUE;
        for (int i = 0; i < amplitudes.length; i++) {
            if (amplitudes[i] != 0.0) {
                minOctave = Math.min(minOctave, i);
                maxOctave = Math.max(maxOctave, i);
            }
        }
        this.valueFactor = 0.16666666666666666 / expectedDeviation(maxOctave - minOctave);
        this.maxValue = (this.first.maxValue() + this.second.maxValue()) * this.valueFactor;
    }

    @Override
    public double maxValue() {
        return this.maxValue;
    }

    @Override
    public double getValue(double x, double y, double z) {
        double x2 = x * INPUT_FACTOR;
        double y2 = y * INPUT_FACTOR;
        double z2 = z * INPUT_FACTOR;
        return (this.first.getValue(x, y, z) + this.second.getValue(x2, y2, z2)) * this.valueFactor;
    }

    public NoiseParameters parameters() {
        return this.parameters;
    }

    private static double expectedDeviation(int octaveSpan) {
        return 0.1 * (1.0 + 1.0 / (octaveSpan + 1));
    }
}
