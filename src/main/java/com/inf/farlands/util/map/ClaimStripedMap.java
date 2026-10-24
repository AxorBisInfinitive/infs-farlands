package com.inf.farlands.util.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * 认领表：long 键映射到持有者编号，0 表示空闲。256 段 StampedLock 加 fastutil 无装箱 map。
 *
 * <p>与 {@link Long2ObjectStripedMap} 的区别只有一处，但它是本质的：认领的 CAS 在段写锁内完成，
 * 调用方拿不到内部的 {@link AtomicLong}，因此不存在锁外持有的引用。这一条让「表里的条目」与
 * 「在途认领」一一对应：释放成功即摘条目，不需要按时间窗清扫，也不会出现「条目已摘而认领仍落在
 * 已脱离对象上」。
 *
 * <p>四条不变量：
 *
 * <ol>
 * <li>表里有条目，其值必非 0；值 0 只在段锁内瞬时存在。</li>
 * <li>认领者不持有锁外引用，故任何一侧的删除都不会被另一侧写回。</li>
 * <li>释放只在 {@code CAS(owner, 0)} 成功时摘条目，刚被别人认领的格子不会被删。</li>
 * <li>编号由调用方全局分配、永不为 0；0 只表示空闲，传 0 给释放一律按不匹配处理。</li>
 * </ol>
 */
public final class ClaimStripedMap {

    private static final int SEG_BITS = 8;
    private static final int SEG_COUNT = 1 << SEG_BITS;
    private static final int SEG_MASK = SEG_COUNT - 1;

    private final Long2ObjectOpenHashMap<AtomicLong>[] segments;
    private final StampedLock[] locks;

    /** expectedCapacity 是预期条目峰值；每段容量取峰值除段数，下限 16，预分配防扩容。 */
    @SuppressWarnings("unchecked")
    public ClaimStripedMap(int expectedCapacity) {
        int perSeg = Math.max(16, (expectedCapacity >> SEG_BITS) + 1);
        segments = new Long2ObjectOpenHashMap[SEG_COUNT];
        locks = new StampedLock[SEG_COUNT];
        for (int i = 0; i < SEG_COUNT; i++) {
            segments[i] = new Long2ObjectOpenHashMap<>(perSeg);
            locks[i] = new StampedLock();
        }
    }

    private int seg(long key) {
        return (int) ((key ^ (key >>> 32)) & SEG_MASK);
    }

    /**
     * 取该键：空闲则写入 owner，已被占则返回 0。
     *
     * <p>「取或建」与 CAS 都在同一把段写锁内完成，所以调用方拿不到内部对象，也就不存在
     * 「拿到之后表被别人摘掉、CAS 落在孤儿对象上」的窗口。
     */
    public long claim(long key, long owner) {
        StampedLock l = locks[seg(key)];
        long stamp = l.writeLock();
        try {
            Long2ObjectOpenHashMap<AtomicLong> m = segments[seg(key)];
            AtomicLong cell = m.get(key);
            if (cell == null) {
                m.put(key, new AtomicLong(owner));
                return owner;
            }
            return cell.compareAndSet(0L, owner) ? owner : 0L;
        } finally {
            l.unlockWrite(stamp);
        }
    }

    /**
     * 放该键：只有当前持有者是 owner 才清零。成功且 {@code dropWhenFree} 为真时连条目一起摘，
     * 让表的大小恒等于在途认领数。
     *
     * @return 本次调用是否真的放掉了
     */
    public boolean release(long key, long owner, boolean dropWhenFree) {
        if (owner == 0L) {
            // 0 表示空闲，不是持有者：CAS(0, 0) 会成功，那会把一个空格子摘掉并回报成功。
            return false;
        }
        StampedLock l = locks[seg(key)];
        long stamp = l.writeLock();
        try {
            Long2ObjectOpenHashMap<AtomicLong> m = segments[seg(key)];
            AtomicLong cell = m.get(key);
            if (cell == null || !cell.compareAndSet(owner, 0L)) {
                return false;
            }
            if (dropWhenFree) {
                m.remove(key);
            }
            return true;
        } finally {
            l.unlockWrite(stamp);
        }
    }

    /** 该键是否被占。乐观读快路径，与 {@link Long2ObjectStripedMap#get} 同一套校验。 */
    public boolean isClaimed(long key) {
        int s = seg(key);
        StampedLock l = locks[s];
        long stamp = l.tryOptimisticRead();
        AtomicLong cell = null;
        boolean inconsistent = false;
        try {
            cell = segments[s].get(key);
        } catch (ArrayIndexOutOfBoundsException e) {
            // 乐观读踩到 rehash 的中间态，按校验失败处理，下面用读锁重读。
            inconsistent = true;
        }
        if (inconsistent || !l.validate(stamp)) {
            stamp = l.readLock();
            try {
                cell = segments[s].get(key);
            } finally {
                l.unlockRead(stamp);
            }
        }
        return cell != null && cell.get() != 0L;
    }
}
