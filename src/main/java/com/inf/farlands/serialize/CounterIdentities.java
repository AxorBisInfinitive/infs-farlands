package com.inf.farlands.serialize;

import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.util.map.Long2ObjectStripedMap;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 记账与段状态共用的身份注册表。
 *
 * <p>身份在 chunk 构造末尾分配，只给服务端的 LevelChunk；卸载时注销。注销之后的身份是死的，一切
 * 写入空转：在途任务，例如装饰收尾、光照完成回调与编码任务，即使晚一步醒来，也不会为同坐标的新
 * 一代 chunk 重建状态。缺了这道门，按坐标共键的旧写法会把旧代的档位直接写进新一代。
 *
 * <p>线程：分配与注销在主线程，判活在 genPool、lightPool 与编码池上，所以注册表用分段 map，
 * 每段独立加锁。
 */
public final class CounterIdentities {

    private static final AtomicLong SEQ = new AtomicLong();

    private static final Long2ObjectStripedMap<Object> LIVE = new Long2ObjectStripedMap<>(1 << 8);

    private static final Object PRESENT = new Object();

    private CounterIdentities() {
    }

    /** 分配并登记一个身份。只由 ChunkAccessMixin 在服务端 LevelChunk 构造末尾调用。 */
    public static long register() {
        long identity = SEQ.incrementAndGet();
        LIVE.put(identity, PRESENT);
        return identity;
    }

    /** 该身份是否还活着。0 恒为假。 */
    public static boolean isLive(long identity) {
        return identity != 0L && LIVE.get(identity) != null;
    }

    /**
     * 该 chunk 是否还持有活着的身份，即它的状态是否仍归本调度器管。
     *
     * <p>卸载流程先注销身份，再清段状态表与记账，而被清掉的 chunk 在 `visibleChunkMap` 的克隆快照
     * 里还会露一阵。自检据此跳过它们：那些 chunk 的段容器可能还有内容，但记账已经按设计清掉了，
     * 拿两者比对只会报出假失配。
     */
    public static boolean owned(LevelChunk chunk) {
        return chunk instanceof CounterIdentity identity && isLive(identity.counterId());
    }

    /** 注销。卸载时调用，幂等。 */
    public static void unregister(long identity) {
        if (identity != 0L) {
            LIVE.remove(identity);
        }
    }

    /** 停服时清空。 */
    public static void clearAll() {
        LIVE.clear();
    }
}
