package com.inf.farlands.simulation.int64.util;

/**
 * 一格的输出，坐标是绝对方块坐标。
 *
 * <p>用绝对坐标而不是段内局部坐标，是为了让这一层不必假设盒子的原点落在哪：段写入方自己减段原点，
 * 换算不在这一层发生。
 */
@FunctionalInterface
public interface Sink {

    void accept(long x, long y, long z, double value);
}
