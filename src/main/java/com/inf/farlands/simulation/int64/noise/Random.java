package com.inf.farlands.simulation.int64.noise;

import net.minecraft.util.RandomSource;

/**
 * 位置相关的随机源工厂，与 vanilla 的 {@code PositionalRandomFactory} 同形，只是 {@link #at} 收 long。
 *
 * <p>不提供 {@code at(BlockPos)} 与 {@code parityConfigString}：前者要把坐标装进 int，后者服务于 vanilla
 * 的测试装置。
 */
public interface Random {

    /**
     * 该方块位置对应的随机源。
     *
     * <p>三轴都在 int 内时必须走 vanilla 的 {@code Mth.getSeed}，那个算式在 int 上做乘法，long 复算得不到同一结果。
     * 越界时用本层的 long 混合，值不同，但不坍缩。
     */
    RandomSource at(long x, long y, long z);

    RandomSource fromHashOf(String name);

    RandomSource fromSeed(long seed);
}
