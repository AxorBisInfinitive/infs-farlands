package com.inf.farlands.simulation.int64.noise.instance;

import java.util.stream.IntStream;

import it.unimi.dsi.fastutil.ints.IntBidirectionalIterator;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * vanilla {@code PerlinNoise} 的逐字移植，两条初始化路都在。
 *
 * <p>现代那条，每个八度的置换表由 {@code forkPositional().fromHashOf("octave_n")} 派生，随机流只在
 * {@code forkPositional()} 那一刻被消费两次 nextLong。legacy 那条，八度逐个直接吃随机流，跳过的八度用
 * {@code consumeCount(262)} 占位。两条的流消费量差一个数量级，且 legacy 的跳过次数也是数据的一部分，少消费
 * 一组，其后每个八度的表都换。
 *
 * <p>{@link #wrap} 与 vanilla 同式，周期 3.3554432E7。它是 Mojang 关掉边境之地的手段，本类照抄、不介入。
 */
public final class PerlinNoise {

    private final ImprovedNoise[] noiseLevels;

    private final double[] amplitudes;

    private final double lowestFreqValueFactor;

    private final double lowestFreqInputFactor;

    private final double maxValue;

    private record Amplitudes(int firstOctave, double[] values) {
    }

    /** 现代初始化。 */
    public static PerlinNoise create(RandomSource random, int firstOctave, double[] amplitudes) {
        return new PerlinNoise(random, new Amplitudes(firstOctave, amplitudes), true);
    }

    /**
     * legacy 初始化。<code>BlendedNoise</code> 用它建三条噪声，八度集合分别是 -15 到 0 与 -7 到 0。
     *
     * <p>八度集合先补成完整幅度表：有列入的八度幅度为 1，其余为 0，首八度取集合最小值的相反数。
     */
    public static PerlinNoise createLegacyForBlendedNoise(RandomSource random, IntStream octaves) {
        IntRBTreeSet set = new IntRBTreeSet();
        octaves.forEach(set::add);
        return new PerlinNoise(random, makeAmplitudes(set), false);
    }

    /** legacy 初始化，显式给首八度与幅度表。旧的下界群系走这条。 */
    public static PerlinNoise createLegacyForLegacyNetherBiome(RandomSource random, int firstOctave,
            double[] amplitudes) {
        return new PerlinNoise(random, new Amplitudes(firstOctave, amplitudes), false);
    }

    private static Amplitudes makeAmplitudes(IntSortedSet octaves) {
        if (octaves.isEmpty()) {
            throw new IllegalArgumentException("Need some octaves!");
        }
        int lowFreqOctaves = -octaves.firstInt();
        int highFreqOctaves = octaves.lastInt();
        int count = lowFreqOctaves + highFreqOctaves + 1;
        if (count < 1) {
            throw new IllegalArgumentException("Total number of octaves needs to be >= 1");
        }
        double[] values = new double[count];
        IntBidirectionalIterator iterator = octaves.iterator();
        while (iterator.hasNext()) {
            int octave = iterator.nextInt();
            values[octave + lowFreqOctaves] = 1.0;
        }
        return new Amplitudes(-lowFreqOctaves, values);
    }

    private PerlinNoise(RandomSource random, Amplitudes source, boolean modernInitialization) {
        this.amplitudes = source.values().clone();
        int firstOctave = source.firstOctave();
        int octaves = this.amplitudes.length;
        int zeroOctaveIndex = -firstOctave;
        this.noiseLevels = new ImprovedNoise[octaves];
        if (modernInitialization) {
            PositionalRandomFactory positional = random.forkPositional();
            for (int i = 0; i < octaves; i++) {
                if (this.amplitudes[i] != 0.0) {
                    int octave = firstOctave + i;
                    this.noiseLevels[i] = new ImprovedNoise(positional.fromHashOf("octave_" + octave));
                }
            }
        } else {
            // 第 0 八度无条件先建，它的幅度为 0 时这份实例被丢弃，但随机流已经消费掉了。
            ImprovedNoise zeroOctave = new ImprovedNoise(random);
            if (zeroOctaveIndex >= 0 && zeroOctaveIndex < octaves && this.amplitudes[zeroOctaveIndex] != 0.0) {
                this.noiseLevels[zeroOctaveIndex] = zeroOctave;
            }
            for (int i = zeroOctaveIndex - 1; i >= 0; i--) {
                if (i < octaves) {
                    if (this.amplitudes[i] != 0.0) {
                        this.noiseLevels[i] = new ImprovedNoise(random);
                    } else {
                        skipOctave(random);
                    }
                } else {
                    skipOctave(random);
                }
            }
            int built = 0;
            for (ImprovedNoise noise : this.noiseLevels) {
                if (noise != null) {
                    built++;
                }
            }
            int declared = 0;
            for (double amplitude : this.amplitudes) {
                if (amplitude != 0.0) {
                    declared++;
                }
            }
            if (built != declared) {
                throw new IllegalStateException(
                        "Failed to create correct number of noise levels for given non-zero amplitudes");
            }
            if (zeroOctaveIndex < octaves - 1) {
                throw new IllegalArgumentException("Positive octaves are temporarily disabled");
            }
        }
        this.lowestFreqInputFactor = Math.pow(2.0, -zeroOctaveIndex);
        this.lowestFreqValueFactor = Math.pow(2.0, octaves - 1) / (Math.pow(2.0, octaves) - 1.0);
        this.maxValue = this.edgeValue(2.0);
    }

    /** 跳过一个八度：vanilla 里就是消费 262 个随机值。 */
    private static void skipOctave(RandomSource random) {
        random.consumeCount(262);
    }

    public double maxValue() {
        return this.maxValue;
    }

    public double getValue(double x, double y, double z) {
        return this.getValue(x, y, z, 0.0, 0.0);
    }

    public double getValue(double x, double y, double z, double yScale, double yFudge) {
        double value = 0.0;
        double factor = this.lowestFreqInputFactor;
        double valueFactor = this.lowestFreqValueFactor;
        for (int i = 0; i < this.noiseLevels.length; i++) {
            ImprovedNoise noise = this.noiseLevels[i];
            if (noise != null) {
                double noiseVal = noise.noise(wrap(x * factor), wrap(y * factor), wrap(z * factor), yScale * factor,
                        yFudge * factor);
                value += this.amplitudes[i] * noiseVal * valueFactor;
            }
            factor *= 2.0;
            valueFactor /= 2.0;
        }
        return value;
    }

    private double edgeValue(double noiseValue) {
        double value = 0.0;
        double valueFactor = this.lowestFreqValueFactor;
        for (int i = 0; i < this.noiseLevels.length; i++) {
            if (this.noiseLevels[i] != null) {
                value += this.amplitudes[i] * noiseValue * valueFactor;
            }
            valueFactor /= 2.0;
        }
        return value;
    }

    /** 与 vanilla 的 {@code PerlinNoise.wrap} 同式。折回之后坐标恒在 1.6777216E7 量级。 */
    public static double wrap(double x) {
        return x - Mth.lfloor(x / 3.3554432E7 + 0.5) * 3.3554432E7;
    }
}
