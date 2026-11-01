package com.inf.farlands.client.render;

/**
 * 帧差：两个位置各拆成「锚点 long 与局部 double」，差值只在同源的两段上做一次。
 *
 * <p>
 * 存在的理由是 double 的表示能力：{@code 2^53} 以上一个 ulp 就超过一格，两个世界量级的位置相减得到的是
 * ulp 网格上的数，不是它们的真实差。锚点承担量级，局部量承担精度，两项分别算再相加即可。
 *
 * <p>
 * 锚点为 0 时，也就是帧未启用时，局部量就是那个 double 本身，于是本方法退化成两个局部量相减，即原版那一次减法。
 * 所以调用方不需要判量级：精度是渐变的，不存在两套公式。
 *
 * <p>
 * 锚点差在 long 上算。两个锚点异号时差可到两倍量级，越过 long，那时真实距离已远超任何可见范围，退回 double
 * 相减即可，结果只影响屏幕外的物体。
 */
public final class FrameMath {

    /** {@code 2^53}：double 在该量级上的 ulp 恰为 2，再往上表示不了一格。 */
    private static final long LIMIT = 1L << 53;

    private FrameMath() {
    }

    /**
     * 一格不再可表示的量级刻度。
     *
     * <p>
     * 只给两处网络插值的开关用：跨网络的位置是 double，超过这个刻度就被舍到 ulp 网格上，插值偏移失去意义。
     * 其余帧差调用点不需要它，因为 {@link #offset} 的精度是渐变的。
     */
    public static double threshold() {
        return (double) LIMIT;
    }

    /** 帧差：锚点差加局部差。锚点为 0 时等于两个局部量相减。 */
    public static double offset(long aAnchor, double aLocal, long bAnchor, double bLocal) {
        long diff;
        try {
            diff = Math.subtractExact(aAnchor, bAnchor);
        } catch (ArithmeticException overflowed) {
            return (double) aAnchor - (double) bAnchor + (aLocal - bLocal);
        }
        return (double) diff + (aLocal - bLocal);
    }
}
