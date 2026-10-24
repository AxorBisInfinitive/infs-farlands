package com.inf.farlands.debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.world.level.ChunkPos;

/**
 * 诊断：派发序号。临时仪器，取到读数后与同批探针一并删除。
 *
 * <p>要回答的问题只有一个：一次准入与下一次同一 chunk 的准入之间，插进了多少次别的派发。序号是全局
 * 单调的，所以「同一格两次派发之间隔了多少」可以直接相减；而同一格累计派发次数给的是频次。
 *
 * <p>作业入口那一行带上本序号，两边按序号对齐之后就能看出：是「作业还没开跑就又派发了一次」，还是
 * 「作业跑完立刻再派发」。这两种形态指向不同的断点。
 *
 * <p>按 chunk 每秒最多一条。全部挂在 {@code logScheduler} 上，关掉即零输出。
 */
public final class DispatchProbe {

    /** 同一 chunk 的派发行最小间隔，单位毫秒。 */
    private static final long INTERVAL_MILLIS = 1000L;

    /** 全局派发序号。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /** 每格的派发状态：上次序号、上次打行的墙钟、累计次数。 */
    private static final Map<Long, Cell> CELLS = new ConcurrentHashMap<>();

    private DispatchProbe() {
    }

    /** 一格的派发状态。主线程写，故用同步块保护。 */
    private static final class Cell {

        private long lastSeq = -1L;

        private long lastLogMillis;

        private int count;
    }

    /**
     * 领一个派发序号并可能打一行。
     *
     * @param pos 本次要派发的 chunk
     * @param pk  准入时该格的欠账段数，即低于 CARVERS 的段数
     * @param n   准入用的段数组长度
     * @return 本次派发的序号，交给作业入口那一行
     */
    public static long next(ChunkPos pos, int pk, int n) {
        long seq = SEQ.incrementAndGet();
        if (!FarlandsConfig.logScheduler) {
            return seq;
        }
        long key = pos.pack();
        Cell cell = CELLS.computeIfAbsent(key, k -> new Cell());
        synchronized (cell) {
            // 首条没有上一条可比，gap 记 -1，不参与相减统计。
            long gap = cell.lastSeq < 0L ? -1L : seq - cell.lastSeq;
            cell.lastSeq = seq;
            cell.count++;
            long now = System.currentTimeMillis();
            if (now - cell.lastLogMillis < INTERVAL_MILLIS) {
                return seq;
            }
            cell.lastLogMillis = now;
            InfsFarlands.LOGGER.info("FLDISP pos={},{} seq={} gap={} cnt={} pk={} n={}",
                    pos.x(), pos.z(), seq, gap, cell.count, pk, n);
        }
        return seq;
    }

    /** 当前全局序号，供作业入口打一栏对照。 */
    public static long current() {
        return SEQ.get();
    }
}
