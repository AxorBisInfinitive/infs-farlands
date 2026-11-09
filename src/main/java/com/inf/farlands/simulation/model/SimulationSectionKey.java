package com.inf.farlands.simulation.model;

/**
 * 模拟段的键：三轴段坐标。
 *
 * <p>
 * 用 long 而不是 int：段号可以超出 int，材质为空的那一段仍然要能当键用。
 */
public record SimulationSectionKey(long x, long y, long z) {
}
