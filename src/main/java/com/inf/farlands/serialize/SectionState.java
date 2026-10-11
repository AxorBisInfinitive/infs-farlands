package com.inf.farlands.serialize;

/**
 * 单个 section 的调度状态。当前只承载档位，即 {@link SectionStage} 的七档。
 *
 * <p>载体从裸 int 换成对象，是为了让后续的所有权与在读标记落在同一处，而不是各开一张表。
 * 本节只换载体，不新增字段：多一个没人读的字段就是多一个没人维护的校验点。
 *
 * <p>档位单调不减，唯一例外是读回按盘上值恢复。字段是 volatile：写点在 genPool、
 * backgroundExecutor 与主线程三处，而编码池的 drainEncode 会读它。
 */
public final class SectionState {

    private volatile int stage;

    public SectionState(int stage) {
        this.stage = stage;
    }

    public int stage() {
        return this.stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }
}
