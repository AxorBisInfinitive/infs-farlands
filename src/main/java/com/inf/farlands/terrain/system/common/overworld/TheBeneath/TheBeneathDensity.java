package com.inf.farlands.terrain.system.common.overworld.TheBeneath;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 虚空之下的地形密度链，把数据包的七个密度函数文件还原成一棵树。
 *
 * <p>对应关系逐条如下，名字即数据包里的文件名：
 * <ul>
 * <li>k 是常量 -0.09。</li>
 * <li>a 是 {@code cache_2d(add(square(noise(spaghetti_3d_1, 1.6, 1.6)), -0.03))}。</li>
 * <li>b 是 {@code cache_2d(add(square(shifted_noise(spaghetti_3d_2, 1.7, 1.7, 三个位移)), -0.04))}。</li>
 * <li>h 是 {@code cache_2d(clamp(0.5 * invert(k) * (a - b) + 0.5, 0, 1))}，即按 a 与 b 的差在两色之间
 * 过渡的权重。</li>
 * <li>h_mul_1_sub_h 是 {@code h * (1 - h)}。</li>
 * <li>mix_a_b_h 是 {@code a + h * (b - a)}，按 h 在两条噪声之间插值。</li>
 * <li>smax_a_b_k 是 {@code mix - k * h * (1 - h)}，即把过渡带抬起来的平滑最大值。</li>
 * <li>final_density 是 {@code -1 * smax}，取负之后密度为正处落成实心。</li>
 * </ul>
 *
 * <p>a、b、h 三个节点各只建一个实例，被多处引用时共享同一个对象。这是契约不是省内存：{@code cache_2d}
 * 的缓存与 {@code NoiseChunk} 的按身份映射都按节点身份认出同一个节点，复制出第二份实例就是两份独立的
 * 缓存，值相同而缓存不复用。
 *
 * <p>整条链没有 interpolated 标记，与数据包一致。26.1.2 的 {@code NoiseChunk} 因此逐格求值，不做角点
 * 插值，代价是每个方块两次噪声采样。
 *
 * <p>噪声实例由本类按 seed 自建，不取世界的那一份。哈希输入是完整的 {@code minecraft:名字}，与
 * {@code Noises.instantiate} 的取法同式。seed 填成世界种子、且宿主维度的随机源不是 legacy 时，建出来的
 * 噪声与世界那两份逐位相同。
 */
public final class TheBeneathDensity {

    /** 两条噪声的注册名，前缀不能省：它是随机流的哈希输入，写错就是另一条噪声。 */
    private static final String SPAGHETTI_3D_1 = "minecraft:spaghetti_3d_1";
    private static final String SPAGHETTI_3D_2 = "minecraft:spaghetti_3d_2";

    /** 两条噪声的参数，与 {@code NoiseData} 里同名条目同值：起始八度 -7，单层振幅 1.0。 */
    private static final int SPAGHETTI_FIRST_OCTAVE = -7;
    private static final double SPAGHETTI_AMPLITUDE = 1.0;

    /** b 节点的三个位移，单位是方块。 */
    private static final double SHIFT_X = 12422.01;
    private static final double SHIFT_Y = 25887.36;
    private static final double SHIFT_Z = 46888.213;

    /** a 与 b 的横向缩放，以及 b 的竖直缩放。 */
    private static final double SPAGHETTI_SCALE = 1.6;
    private static final double SHIFTED_SCALE = 1.7;

    /** a 与 b 各自的下移量，决定噪声平方后多厚的一层算空。 */
    private static final double A_BIAS = -0.03;
    private static final double B_BIAS = -0.04;

    /** h 的陡峭度：差值除以它再折半，越小过渡带越窄。 */
    private static final double H_SLOPE = -0.09;

    private TheBeneathDensity() {
    }

    /**
     * 建整条 final_density。返回的树不可变，可以跨线程与跨 {@code NoiseChunk} 共享：每个 {@code NoiseChunk}
     * 自己按节点身份建缓存层，树的节点本身没有可变状态。
     */
    public static DensityFunction build(long seed) {
        PositionalRandomFactory root = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(seed).forkPositional();

        DensityFunction noiseA = TheBeneathNoise.of(holder(create(root, SPAGHETTI_3D_1)),
                SPAGHETTI_SCALE, SPAGHETTI_SCALE);
        DensityFunction noiseB = TheBeneathShiftedNoise.of(
                constant(SHIFT_X), constant(SHIFT_Y), constant(SHIFT_Z),
                SHIFTED_SCALE, SHIFTED_SCALE, holder(create(root, SPAGHETTI_3D_2)));

        DensityFunction a = cache2d(add(square(noiseA), constant(A_BIAS)));
        DensityFunction b = cache2d(add(square(noiseB), constant(B_BIAS)));
        DensityFunction k = constant(H_SLOPE);

        DensityFunction h = cache2d(clamp(
                add(mul(mul(constant(0.5), invert(k)), add(a, mul(constant(-1.0), b))), constant(0.5)),
                0.0, 1.0));
        DensityFunction hTimesOneMinusH = mul(add(constant(1.0), mul(h, constant(-1.0))), h);
        DensityFunction mix = add(a, mul(h, add(b, mul(constant(-1.0), a))));
        return mul(constant(-1.0), add(mix, mul(constant(-1.0), mul(k, hTimesOneMinusH))));
    }

    /**
     * 一条噪声。参数是硬编码的两组常量，因此不读注册表：数据包也只用到这两条，读表会把系统绑上宿主世界的
     * 数据包内容。
     */
    private static NormalNoise create(PositionalRandomFactory root, String path) {
        return NormalNoise.create(root.fromHashOf(path),
                new NormalNoise.NoiseParameters(SPAGHETTI_FIRST_OCTAVE, SPAGHETTI_AMPLITUDE));
    }

    /**
     * 把噪声接进 holder。holder 的 noiseData 用直接持有，没有资源键：本链不按名字反查，key 留着只会引来
     * 一次没人用的注册表查询。
     */
    private static DensityFunction.NoiseHolder holder(NormalNoise noise) {
        return new DensityFunction.NoiseHolder(Holder.direct(noise.parameters()), noise);
    }

    private static DensityFunction add(DensityFunction first, DensityFunction second) {
        return DensityFunctions.add(first, second);
    }

    private static DensityFunction mul(DensityFunction first, DensityFunction second) {
        return DensityFunctions.mul(first, second);
    }

    private static DensityFunction constant(double value) {
        return DensityFunctions.constant(value);
    }

    private static DensityFunction cache2d(DensityFunction argument) {
        return DensityFunctions.cache2d(argument);
    }

    private static DensityFunction square(DensityFunction argument) {
        return TheBeneathMapped.square(argument);
    }

    private static DensityFunction invert(DensityFunction argument) {
        return TheBeneathMapped.invert(argument);
    }

    private static DensityFunction clamp(DensityFunction argument, double min, double max) {
        return TheBeneathClamp.of(argument, min, max);
    }
}
