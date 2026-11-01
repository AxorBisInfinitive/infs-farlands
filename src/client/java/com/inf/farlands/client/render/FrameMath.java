package com.inf.farlands.client.render;

/**
 * 帧表示的两个工具：两锚点之差是否安全，以及帧差是多少。
 *
 * <p>
 * 锚点是 long，两个锚点之差在异号时可以达到两倍量级，越过 long。所以先判号，再判量级；同号时
 * {@code |a - b|} 不超过 {@code max(|a|,|b|)}，减法本身安全。
 *
 * <p>
 * 渲染侧启用帧的量级阈值是 {@code 2^53}：double 在该量级以上表示不了一格，插值结果只能落在 ulp 网格上。
 * 低于它时调用方一律走原式，逐位不变。
 */
public final class FrameMath {

    /** {@code 2^53}：double 的整数位宽，超过它一格就表示不了。 */
    private static final long LIMIT = 1L << 53;

    private FrameMath() {
    }

    /** 渲染侧阈值，坐标为double时与 {@link #LIMIT} 同值。 */
    public static double threshold() {
        return (double) LIMIT;
    }

    /** 两锚点之差是否能落进 long：只有异号时才可能越界。 */
    public static boolean safeDelta(long a, long b) {
        if ((a < 0L) == (b < 0L)) {
            return true;
        }
        return a >= -LIMIT && a <= LIMIT && b >= -LIMIT && b <= LIMIT;
    }

    /** 三轴里是否有任一轴已到渲染侧需要帧的量级。 */
    public static boolean wide(double x, double y, double z) {
        double limit = threshold();
        return Math.abs(x) >= limit || Math.abs(y) >= limit || Math.abs(z) >= limit;
    }

    /** 两组锚点在三个轴上都能安全相减。 */
    public static boolean safe(long ax, long ay, long az, long bx, long by, long bz) {
        return safeDelta(ax, bx) && safeDelta(ay, by) && safeDelta(az, bz);
    }

    /** 帧差：锚点差加局部差。调用前必须先过 {@link #safeDelta}。 */
    public static double offset(long aAnchor, double aLocal, long bAnchor, double bLocal) {
        return (double) (aAnchor - bAnchor) + (aLocal - bLocal);
    }
}
