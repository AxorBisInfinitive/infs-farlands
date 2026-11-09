package com.inf.farlands.simulation.int64.util;

/**
 * long 坐标的半开方块盒，区间是 [min, max)。
 *
 * <p>段内填充用 {@link #ofSection}，任意区域用四角构造。本类不校验上下界：这一层不设防，范围由调用方负责。
 */
public record LongBox(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {

    /** 一个段覆盖的方块盒。段号乘 16 走 long，段号越过 int 时结果仍精确。 */
    public static LongBox ofSection(long sectionX, long sectionY, long sectionZ) {
        long x = sectionX * 16L;
        long y = sectionY * 16L;
        long z = sectionZ * 16L;
        return new LongBox(x, y, z, x + 16L, y + 16L, z + 16L);
    }
}
