package com.inf.farlands.simulation.int1024.noise;

import love.crysh.long2.ints.Int1024;

import net.minecraft.util.RandomSource;

/**
 * 位置相关的随机源工厂，与 vanilla 的 {@code PositionalRandomFactory} 同形，只是 {@link #at} 收 1024 位坐标。
 *
 * <p>不提供 {@code at(BlockPos)} 与 {@code parityConfigString}：前者要把坐标装进 int，后者服务于 vanilla
 * 的测试装置。
 *
 * <p>当前无实现，也没有调用方：填充器给 {@code createAquifer} 传的是 {@code null}，两个内置系统都不读它。接真实
 * 含水层时连同实现一起落。
 */
public interface Random {

    /**
     * 该方块位置对应的随机源。
     *
     * <p>三轴都在 int 内时必须走 vanilla 的 {@code Mth.getSeed}：那个算式在 int 上做乘法，换更宽的宽度复算得不到
     * 同一结果。超出 int 时用本层自己的混合，值不同，但不坍缩。
     */
    RandomSource at(Int1024 x, Int1024 y, Int1024 z);

    RandomSource fromHashOf(String name);

    RandomSource fromSeed(long seed);
}
