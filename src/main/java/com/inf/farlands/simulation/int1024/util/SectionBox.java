package com.inf.farlands.simulation.int1024.util;

import com.inf.long2.ints.Int1024;

/**
 * 一段的方块范围：三轴原点加边长 16，逐轴的十六个绝对坐标在构造时算好。
 *
 * <p>
 * 不存绝对上界：顶端段的原点加 16 恰是 {@code 2^1023}，比 {@code Int1024.MAX_VALUE} 大 1，不可表示。所以范围只记
 * 原点和边长，遍历一律按段内偏移 0 到 15 数。
 *
 * <p>
 * 十六个坐标提前算成数组，逐格求值因此只引用，不再每格做一次加法分配。一段 48 个对象，相对四千格的求值量可以
 * 忽略；这也是本类存在的主要理由。
 */
public final class SectionBox {

    /** 段边长。 */
    public static final int SIZE = 16;

    private final Int1024 originX;

    private final Int1024 originY;

    private final Int1024 originZ;

    private final Int1024[] xs;

    private final Int1024[] ys;

    private final Int1024[] zs;

    public SectionBox(Int1024 originX, Int1024 originY, Int1024 originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.xs = farlands$axis(originX);
        this.ys = farlands$axis(originY);
        this.zs = farlands$axis(originZ);
    }

    /**
     * 段号乘 16 得原点，走 {@code shift(4)}。
     *
     * <p>
     * 左移只对段号成立：段号最大 {@code 2^1019-1}，左移四位是 {@code 2^1023-16}，不溢出；方块坐标贴顶时左移会
     * 静默回绕。
     */
    public static SectionBox ofSection(Int1024 sectionX, Int1024 sectionY, Int1024 sectionZ) {
        return new SectionBox(sectionX.shift(4), sectionY.shift(4), sectionZ.shift(4));
    }

    /** 该轴的十六个绝对坐标，索引是段内偏移。 */
    private static Int1024[] farlands$axis(Int1024 origin) {
        Int1024[] axis = new Int1024[SIZE];
        for (int offset = 0; offset < SIZE; offset++) {
            axis[offset] = origin.add(Int1024.of((long) offset));
        }
        return axis;
    }

    public Int1024 originX() {
        return this.originX;
    }

    public Int1024 originY() {
        return this.originY;
    }

    public Int1024 originZ() {
        return this.originZ;
    }

    /** 段内偏移 {@code local} 处的绝对 X，取值 0 到 15。 */
    public Int1024 x(int local) {
        return this.xs[local];
    }

    public Int1024 y(int local) {
        return this.ys[local];
    }

    public Int1024 z(int local) {
        return this.zs[local];
    }
}
