package com.inf.farlands.util.pos;

import com.inf.long2.binaries.Binary256;

/**
 * 命令层到传送层的精确目标侧通道。
 *
 * <p>
 * 命令的坐标参数最终是 double，精度在解析那一步就丢了，而传送的写入点拿不到原文。所以解析侧把三轴精确值放在
 * 这里，由传送的写入点取走。两侧都在服务端线程上，同一次命令同步执行，不存在跨线程可见性问题。
 *
 * <p>
 * 生命周期由命令自己划：解析成功时写入，命令结束时清掉。没有写入时 {@link #pending()} 返回 null，写入点据此
 * 完全不介入，行为与不装本模组相同。
 */
public final class ExactTeleport {

    private static final ThreadLocal<Binary256[]> PENDING = new ThreadLocal<>();

    private ExactTeleport() {
    }

    /** 三轴精确目标，未设置时为 null。相对坐标存的是增量。 */
    public static Binary256[] pending() {
        return PENDING.get();
    }

    /** 写入三轴精确目标。 */
    public static void set(Binary256 x, Binary256 y, Binary256 z) {
        PENDING.set(new Binary256[] { x, y, z });
    }

    /** 清掉本次命令的精确目标。 */
    public static void clear() {
        PENDING.remove();
    }
}
