package com.inf.farlands.serialize;

import com.inf.farlands.util.map.Long2ObjectStripedMap;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 每 chunk 的两个一次性标志，按身份记账。本节只存状态，不参与任何生成判据。
 *
 * <p>{@code windowReadDone} 是「窗口面读过一次盘」，第七行补 FULL 之前要它为真；{@code fullDone} 是
 * 「FULL 已补」的单次令牌，因为 {@code completeFuture} 对已完成的 future 再调会抛。
 *
 * <p>键是身份编号，不是坐标。坐标共键会让三类写者混进同一格：单机下客户端与服务端两个同坐标的
 * chunk、同坐标的前一代与后一代，以及后一代构造时继承前一代的状态。身份只发给服务端的 LevelChunk。
 *
 * <p>条目在首次写标志时懒建，读方不建：没有条目等价于两个标志都为假。这一点是契约，不是优化。
 * 删掉懒建而只留 {@code get} 会让两个写方永远空转，下游第七行因此永不成立，FULL 永远补不上。
 *
 * <p>线程：写点来自主线程与后台线程，所以标志的读写都在实例上同步。
 */
public final class ChunkCounters {

    private static final Long2ObjectStripedMap<ChunkCounters> COUNTERS = new Long2ObjectStripedMap<>(1 << 12);

    /** 窗口内段读过一次盘即为真，一次性。 */
    private boolean windowReadDone;

    /** FULL 已补过，一次性。令牌：completeFuture 对已完成的 future 再调会抛。 */
    private boolean fullDone;

    private ChunkCounters() {
    }

    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof CounterIdentity identity ? identity.counterId() : 0L;
    }

    /**
     * 取或建该身份的标志实例，只给写方用。读方不得建条目。
     *
     * <p>建完再判一次活：判活与建表之间可能刚好被注销，那张刚建的表要立刻收回，否则它会成为没人
     * 再清的残留。
     */
    private static ChunkCounters ofLive(long identity) {
        ChunkCounters counters = COUNTERS.computeIfAbsent(identity, k -> new ChunkCounters());
        if (!CounterIdentities.isLive(identity)) {
            COUNTERS.remove(identity);
            return null;
        }
        return counters;
    }

    // ---- 生命周期 ----

    /** chunk 卸载后清掉它自己那一格。按身份清，清不到同坐标的后一代。 */
    public static void clear(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity != 0L) {
            COUNTERS.remove(identity);
        }
    }

    /** 停服时清空。 */
    public static void clearAll() {
        COUNTERS.clear();
    }

    /** 置窗口读回闩。窗口内段读过一次盘即为真，一次性；卸载随实例消亡。 */
    public static void markWindowReadDone(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return;
        }
        ChunkCounters c = ofLive(identity);
        if (c == null) {
            return;
        }
        synchronized (c) {
            c.windowReadDone = true;
        }
    }

    /** 窗口读回闩是否已置。身份为 0 或从未记账过返回假。 */
    public static boolean isWindowReadDone(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return false;
        }
        ChunkCounters c = COUNTERS.get(identity);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            return c.windowReadDone;
        }
    }

    /** FULL 补发令牌：取到才算本次补。身份为 0 返回假。 */
    public static boolean claimFull(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return false;
        }
        ChunkCounters c = ofLive(identity);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            if (c.fullDone) {
                return false;
            }
            c.fullDone = true;
            return true;
        }
    }

    /**
     * 只读查 FULL 令牌是否已用，供加载页的网格读数。
     *
     * <p>不能拿 {@link #claimFull} 当查询：那是令牌，读一次就把这次补发吃掉，该 chunk 的 FULL 从此
     * 再也补不上，加载页也就永不关闭。本方法与 {@link #isWindowReadDone} 同形，只读不写。
     */
    public static boolean isFullDone(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return false;
        }
        ChunkCounters c = COUNTERS.get(identity);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            return c.fullDone;
        }
    }
}
