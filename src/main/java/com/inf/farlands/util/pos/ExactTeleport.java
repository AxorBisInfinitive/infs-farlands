package com.inf.farlands.util.pos;

import love.crysh.long2.binaries.binary1280.Binary1280;

/**
 * 命令层到传送层的精确目标侧通道。
 *
 * <p>
 * 命令的坐标参数最终是 double，精度在解析那一步就丢了，而传送的写入点拿不到原文。所以解析侧把三轴精确值放在
 * 这里，由传送的写入点取走。两侧都在服务端线程上，同一次命令同步执行，不存在跨线程可见性问题。
 *
 * <p>
 * 两个通道，语义不同，同一次解析里一起写、一起清。{@link #pending()} 那份按「绝对轴落值、相对轴增量」交给传送
 * 写入点，它要按受传者的精确位置去加；{@link #echo()} 那份已经是加过命令源位置的最终目标，只供命令回显打印，
 * 不参与落点。
 *
 * <p>
 * 生命周期由命令自己划：解析成功时写入，命令结束时清掉。没有写入时 {@link #pending()} 返回 null，写入点据此
 * 完全不介入，行为与不装本模组相同。
 */
public final class ExactTeleport {

    private static final ThreadLocal<Binary1280[]> PENDING = new ThreadLocal<>();

    /** 回显用的三轴绝对目标，未设置时为 null。 */
    private static final ThreadLocal<Binary1280[]> ECHO = new ThreadLocal<>();

    private ExactTeleport() {
    }

    /** 三轴精确目标，未设置时为 null。相对坐标存的是增量。 */
    public static Binary1280[] pending() {
        return PENDING.get();
    }

    /** 写入三轴精确目标。 */
    public static void set(Binary1280 x, Binary1280 y, Binary1280 z) {
        PENDING.set(new Binary1280[] { x, y, z });
    }

    /** 回显用的三轴绝对目标，未设置时为 null。 */
    public static Binary1280[] echo() {
        return ECHO.get();
    }

    /** 写入回显用的三轴绝对目标。 */
    public static void setEcho(Binary1280 x, Binary1280 y, Binary1280 z) {
        ECHO.set(new Binary1280[] { x, y, z });
    }

    /** 清掉本次命令的精确目标，两个通道一并清。 */
    public static void clear() {
        PENDING.remove();
        ECHO.remove();
    }
}
