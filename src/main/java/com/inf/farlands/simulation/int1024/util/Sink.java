package com.inf.farlands.simulation.int1024.util;

import com.inf.long2.ints.Int1024;

/**
 * 一格的输出，坐标是绝对方块坐标。
 *
 * <p>
 * 用绝对坐标而不是段内局部坐标，是为了让这一层不必假设盒子的原点落在哪：写入方自己取低位掩码换算段内下标，
 * 换算不在这一层发生。
 */
@FunctionalInterface
public interface Sink {

    void accept(Int1024 x, Int1024 y, Int1024 z, double value);
}
