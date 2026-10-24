package com.inf.farlands.util.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.concurrent.locks.StampedLock;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * 无装箱分段 map：fastutil Long2ObjectOpenHashMap × 256 段 + StampedLock。
 */
public final class Long2ObjectStripedMap<V> {

    private static final int SEG_BITS = 8;
    private static final int SEG_COUNT = 1 << SEG_BITS;
    private static final int SEG_MASK = SEG_COUNT - 1;

    private final Long2ObjectOpenHashMap<V>[] segments;
    private final StampedLock[] locks;

    /** expectedCapacity = 预期条目峰值；每段容量 = peak/256，下限 16，预分配防扩容。 */
    @SuppressWarnings("unchecked")
    public Long2ObjectStripedMap(int expectedCapacity) {
        int perSeg = Math.max(16, (expectedCapacity >> SEG_BITS) + 1);
        segments = new Long2ObjectOpenHashMap[SEG_COUNT];
        locks = new StampedLock[SEG_COUNT];
        for (int i = 0; i < SEG_COUNT; i++) {
            segments[i] = new Long2ObjectOpenHashMap<>(perSeg);
            locks[i] = new StampedLock();
        }
    }

    /** 分段：key 高/低位混合后取低 8 位，与 CHM spread 同思路，均匀。 */
    private int seg(long key) {
        return (int) ((key ^ (key >>> 32)) & SEG_MASK);
    }

    /**
     * 乐观读是无锁快路径，写中读则 validate 失败 -> readLock 重读，保证一致。
     * fastutil 的 key 数组非 volatile，rehash时乐观读可能看到中间态
     * 重读，rehash 与写/读锁互斥，读锁内必然一致。
     */
    public V get(long key) {
        StampedLock l = locks[seg(key)];
        long stamp = l.tryOptimisticRead();
        V v;
        try {
            v = segments[seg(key)].get(key);
        } catch (ArrayIndexOutOfBoundsException e) {
            stamp = l.readLock();
            try {
                v = segments[seg(key)].get(key);
            } finally {
                l.unlockRead(stamp);
            }
            return v;
        }
        if (!l.validate(stamp)) {
            stamp = l.readLock();
            try {
                v = segments[seg(key)].get(key);
            } finally {
                l.unlockRead(stamp);
            }
        }
        return v;
    }

    public V put(long key, V val) {
        StampedLock l = locks[seg(key)];
        long stamp = l.writeLock();
        try {
            return segments[seg(key)].put(key, val);
        } finally {
            l.unlockWrite(stamp);
        }
    }

    /** 返回被删旧值；null 表示不存在。 */
    public V remove(long key) {
        StampedLock l = locks[seg(key)];
        long stamp = l.writeLock();
        try {
            return segments[seg(key)].remove(key);
        } finally {
            l.unlockWrite(stamp);
        }
    }

    /** 返回旧值；null 表示首次，语义与 CHM.putIfAbsent 一致。 */
    public V putIfAbsent(long key, V val) {
        StampedLock l = locks[seg(key)];
        long stamp = l.writeLock();
        try {
            return segments[seg(key)].putIfAbsent(key, val);
        } finally {
            l.unlockWrite(stamp);
        }
    }

    /**
     * 已存在则不重算，storage.getOrCreate 依赖此行为。mapping 轻量且不回调 map。
     *
     * <p>返回的是内部对象，调用方在锁外持有它，所以「拿到的对象可能已被别的线程摘掉」这件事由调用方
     * 自己承担。需要「条目存在等价于在途持有」的场合不要用它，用 {@link ClaimStripedMap}。
     */
    public V computeIfAbsent(long key, LongFunction<V> mapping) {
        StampedLock l = locks[seg(key)];
        long stamp = l.writeLock();
        try {
            Long2ObjectOpenHashMap<V> m = segments[seg(key)];
            V v = m.get(key);
            if (v == null) {
                v = mapping.apply(key);
                m.put(key, v);
            }
            return v;
        } finally {
            l.unlockWrite(stamp);
        }
    }

    public boolean containsKey(long key) {
        StampedLock l = locks[seg(key)];
        long stamp = l.readLock();
        try {
            return segments[seg(key)].containsKey(key);
        } finally {
            l.unlockRead(stamp);
        }
    }

    /** 逐段写锁内 removeIf。 */
    public void removeIf(Predicate<V> predicate) {
        for (int i = 0; i < SEG_COUNT; i++) {
            StampedLock l = locks[i];
            long stamp = l.writeLock();
            try {
                segments[i].values().removeIf(predicate);
            } finally {
                l.unlockWrite(stamp);
            }
        }
    }

    /** 清空全部分段。调用点都在静止状态，没有并发读写。 */
    public void clear() {
        for (int i = 0; i < SEG_COUNT; i++) {
            StampedLock l = locks[i];
            long stamp = l.writeLock();
            try {
                segments[i].clear();
            } finally {
                l.unlockWrite(stamp);
            }
        }
    }

    public int size() {
        int n = 0;
        for (int i = 0; i < SEG_COUNT; i++) {
            StampedLock l = locks[i];
            long stamp = l.readLock();
            try {
                n += segments[i].size();
            } finally {
                l.unlockRead(stamp);
            }
        }
        return n;
    }

    public boolean isEmpty() {
        for (int i = 0; i < SEG_COUNT; i++) {
            StampedLock l = locks[i];
            long stamp = l.readLock();
            try {
                if (!segments[i].isEmpty()) {
                    return false;
                }
            } finally {
                l.unlockRead(stamp);
            }
        }
        return true;
    }
}
