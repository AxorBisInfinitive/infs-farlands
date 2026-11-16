package com.inf.farlands.simulation.model;

import java.util.Objects;

import com.inf.long2.ints.Int1024;

/**
 * 模拟段的键：三轴段坐标。
 *
 * <p>
 * 三轴是 {@link Int1024}：段号的可表示范围是 {@code [-2^1019, 2^1019-1]}，1020 位，int 与 long 都装不下。
 *
 * <p>
 * 不用 record 而是自建类，只为缓存哈希。一次窗口变化会建两万八千多个键，每个键在入表、进层桶、判在表这几处
 * 各被哈希一次，重复算同一份十六组字是不必要的。哈希在构造时算一次，按三轴各自的 {@code hashCode} 依次组合，
 * 与 record 的组合方式同值。
 *
 * <p>
 * {@code equals} 走两级快路径：先比最低一组字的原值，再比缓存哈希，最后才逐组比较。最低一组在窗口内区分度
 * 最高，因为同一个中心加几十以内的偏移只改低位。
 *
 * <p>
 * 三轴对象通常来自调用方的预计算数组，多个键共享同一个 {@code Int1024} 实例。{@code Int1024} 不可变，
 * 共享没有别名问题。
 */
public final class SimulationSectionKey {

    private final Int1024 x;

    private final Int1024 y;

    private final Int1024 z;

    /** 构造时算定的哈希，与三轴内容一一对应。 */
    private final int hash;

    /** 段坐标。 */
    public SimulationSectionKey(Int1024 x, Int1024 y, Int1024 z) {
        this.x = Objects.requireNonNull(x);
        this.y = Objects.requireNonNull(y);
        this.z = Objects.requireNonNull(z);
        this.hash = farlands$hash(this.x, this.y, this.z);
    }

    public Int1024 x() {
        return this.x;
    }

    public Int1024 y() {
        return this.y;
    }

    public Int1024 z() {
        return this.z;
    }

    /** 三轴依次组合，与 record 的 {@code hashCode} 同式同值。 */
    private static int farlands$hash(Int1024 x, Int1024 y, Int1024 z) {
        int hash = x.hashCode();
        hash = 31 * hash + y.hashCode();
        return 31 * hash + z.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SimulationSectionKey other)) {
            return false;
        }
        // 最低一组字在窗口内区分度最高，先比它比先比哈希更能早退。
        if (this.x.getLong(15) != other.x.getLong(15)) {
            return false;
        }
        if (this.hash != other.hash) {
            return false;
        }
        return this.x.equals(other.x) && this.y.equals(other.y) && this.z.equals(other.z);
    }

    /** 与今天的 record 同形，按坐标打印。 */
    @Override
    public String toString() {
        return "SimulationSectionKey[x=" + this.x + ", y=" + this.y + ", z=" + this.z + "]";
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
