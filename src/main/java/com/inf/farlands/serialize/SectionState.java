package com.inf.farlands.serialize;

/**
 * 单个 section 的调度状态：档位、标志与持有者。
 *
 * <p>档位与标志跨线程可见：编码池读档位与 DIRTY，下发侧读 DIRTY，读回侧读 READING。持有者是主线程
 * 独占的普通字段，只由所有权表读写。
 *
 * <p>标志的读改写在实例锁内，读是 volatile 读。调度器接管之后写者收成主线程一个。
 */
public final class SectionState {

    /** 本段有未落盘的改动。 */
    public static final int DIRTY = 1;

    /** 本段正在从磁盘读回。 */
    public static final int READING = 1 << 1;

    private volatile int stage;

    private volatile int flags;

    private long owner;

    public SectionState(int stage) {
        this.stage = stage;
    }

    public int stage() {
        return this.stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }

    public synchronized void markDirty() {
        this.flags |= DIRTY;
    }

    public synchronized void clearDirty() {
        this.flags &= ~DIRTY;
    }

    public boolean isDirty() {
        return (this.flags & DIRTY) != 0;
    }

    public synchronized void markReading() {
        this.flags |= READING;
    }

    public synchronized void clearReading() {
        this.flags &= ~READING;
    }

    public boolean isReading() {
        return (this.flags & READING) != 0;
    }

    /** 主线程独占。 */
    public long owner() {
        return this.owner;
    }

    /** 主线程独占。 */
    public void setOwner(long owner) {
        this.owner = owner;
    }
}
