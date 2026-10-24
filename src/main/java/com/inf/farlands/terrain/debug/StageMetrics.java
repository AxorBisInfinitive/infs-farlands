package com.inf.farlands.terrain.debug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

/**
 * 就绪吞吐读数：每窗口各一次，报 chunk 与 section 的完成速率，以及五个阶段各自的耗时。
 *
 * <p>
 * 两个 cum 率的分子是「会话累计」，分母必须是「会话秒数」，不能是窗口秒数：拿累计数除窗口秒数会让
 * cum 率每窗口被放大一次，读数随会话单调虚高，而窗口那两个率才是当下速率。
 *
 * <p>
 * 事件口径：一个 chunk 在它的 lightChunk 回调成功、段被升到 LIGHTED 那一刻计一次；section 按
 * 同一次回调里升级的段数累加。所以 cps 衡量的是整条生成链走完的速率，不是某一阶段的速率。
 *
 * <p>
 * 前五个阶段报的是线程时间：段数按「本次真的升了多少段」累加，时间是该阶段的工作占用了多少线程时间；
 * 装饰跑在池上并行，它的毫秒之和可以超过窗口墙钟。光照那一档报的是延迟，即 lightChunk 从提交到回调
 * 执行的墙钟，含排队等待，所以按「完成 chunk 数 / 每 chunk 平均毫秒」报，不能与前五列的毫秒相加。
 */
public final class StageMetrics {

    /**
     * 打进日志的窗口 tick 数，只作标记。
     *
     * <p>
     * 它不是触发间隔：调用方按自己的周期决定何时调 {@link #flush()}，本类不检查 tick 计数。所以这个值
     * 必须与调用方那个周期一致，否则日志里的窗口长度会与实际不符。
     */
    public static final int WINDOW_TICKS = 200;

    // ---- 阶段耗时读数 ----
    //
    // 下标与 STAGE_NAMES 同序。段数在「真的升段」处累加，时间在「该阶段的工作调用」处累加。

    public static final int STAGE_BIOMES = 0;
    public static final int STAGE_TERRAIN = 1;
    public static final int STAGE_SURFACE = 2;
    public static final int STAGE_CARVERS = 3;
    public static final int STAGE_DECORATED = 4;
    public static final int STAGE_LIGHTED = 5;

    /** 阶段总数，含光照。 */
    private static final int STAGE_COUNT = 6;

    /** 前五阶段的日志名，光照单列。 */
    private static final String[] STAGE_NAMES = { "bio", "ter", "sur", "car", "dec" };

    private static final AtomicLong[] STAGE_NANOS = new AtomicLong[STAGE_COUNT];
    private static final AtomicLong[] STAGE_SECTIONS = new AtomicLong[STAGE_COUNT];

    static {
        for (int i = 0; i < STAGE_COUNT; i++) {
            STAGE_NANOS[i] = new AtomicLong();
            STAGE_SECTIONS[i] = new AtomicLong();
        }
    }

    private static final AtomicLong SECTIONS_IN = new AtomicLong();
    private static final AtomicLong SECTIONS_LIGHTED = new AtomicLong();
    private static final AtomicLong CHUNKS_LIGHTED = new AtomicLong();

    /**
     * 本窗走过存在流程的 chunk 数，即新建了多少个「壳」。
     *
     * <p>
     * 新世界里壳是建出来的不是读出来的，所以它是加载供给的唯一读数。
     */
    private static final AtomicLong CHUNK_LOADS = new AtomicLong();

    /**
     * 走完存在流程的 chunk 数。与 {@link #CHUNK_LOADS} 相减就是仍在流程里的壳数：它高说明流程自己是
     * 闸门，它接近零说明壳卡在票据到加载那一段。
     */
    private static final AtomicLong CHUNK_LOAD_DONE = new AtomicLong();

    /**
     * 上次打印时 {@link #CHUNK_LOADS} 的读数。窗口速率取两次之差，而不是把总数清零：在途量要靠两个
     * 总数相减，清掉任何一个都会让它变成负数。
     */
    private static long lastLoadTotal;

    /** 上次 flush 时刻，只用于算窗口秒数。 */
    private static long anchorNanos = System.nanoTime();
    private static long anchorSectionsLighted;
    private static long anchorChunksLighted;
    /** 累计秒数。累计率的分母必须是它，不能是窗口秒数。 */
    private static double cumSeconds;

    private StageMetrics() {
    }

    /** 一个 chunk 完成点亮。 */
    public static void lightedChunk() {
        CHUNKS_LIGHTED.incrementAndGet();
    }

    /** 一次点亮回调里升级的段数。 */
    public static void lightedSections(int upgraded) {
        if (upgraded > 0) {
            SECTIONS_LIGHTED.addAndGet(upgraded);
        }
    }

    /** 进入 BIOMES 的段数，即进入管线的段数。 */
    public static void sectionsIn(long n) {
        if (n > 0L) {
            SECTIONS_IN.addAndGet(n);
        }
    }

    /** 某阶段这一次推进占用的纳秒。非正数忽略。光照那一段传的是延迟，见类注释。 */
    public static void stageWork(int stage, long nanos) {
        if (nanos > 0L) {
            STAGE_NANOS[stage].addAndGet(nanos);
        }
    }

    /** 某阶段这一次推进的段数。非正数忽略。 */
    public static void stageSections(int stage, int sections) {
        if (sections > 0) {
            STAGE_SECTIONS[stage].addAndGet(sections);
        }
    }

    /** 一个 chunk 走进存在流程。 */
    public static void chunkLoaded(long chunkKey) {
        CHUNK_LOADS.incrementAndGet();
    }

    /** 一个 chunk 的存在流程收尾。 */
    public static void chunkLoadDone() {
        CHUNK_LOAD_DONE.incrementAndGet();
    }

    /** 前五阶段的「段数/毫秒/每段微秒」，光照是「完成 chunk 数/每 chunk 延迟毫秒」。 */
    private static String stageLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < STAGE_COUNT; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            long nanos = STAGE_NANOS[i].get();
            if (i == STAGE_LIGHTED) {
                long chunks = CHUNKS_LIGHTED.get();
                double perChunkMs = chunks > 0L ? nanos / 1.0E6 / chunks : 0.0;
                sb.append("lit=").append(chunks).append('/').append(fmt(perChunkMs)).append("ms");
                continue;
            }
            long sections = STAGE_SECTIONS[i].get();
            sb.append(STAGE_NAMES[i]).append('=').append(sections).append('/').append(fmt(nanos / 1.0E6))
                    .append("ms/")
                    .append(sections > 0 ? fmt(nanos / 1.0E6 / sections * 1000.0) : "0.00").append("us");
        }
        return sb.toString();
    }

    /**
     * 每 {@link #WINDOW_TICKS} tick 一次，主线程调用。先打印本窗口，再把窗口锚点推到现在。
     *
     * <p>
     * 只报读数，不报解读。
     */
    public static void flush() {
        long now = System.nanoTime();
        double windowSeconds = Math.max(1.0E-3, (now - anchorNanos) / 1.0E9);
        cumSeconds += windowSeconds;
        long sectionsIn = SECTIONS_IN.get();
        long sectionsLighted = SECTIONS_LIGHTED.get();
        long chunksLighted = CHUNKS_LIGHTED.get();

        if (FarlandsConfig.logStageMetrics) {
            InfsFarlands.LOGGER.info(
                    "FLSTAGE window={}t elapsed={}s cps={} sps={} cum_cps={} cum_sps={} sectIn={} loads={} loadPending={}",
                    WINDOW_TICKS, fmt(windowSeconds),
                    fmt(chunksLighted / windowSeconds), fmt(sectionsLighted / windowSeconds),
                    fmt((anchorChunksLighted + chunksLighted) / cumSeconds),
                    fmt((anchorSectionsLighted + sectionsLighted) / cumSeconds),
                    sectionsIn,
                    fmt((CHUNK_LOADS.get() - lastLoadTotal) / windowSeconds),
                    CHUNK_LOADS.get() - CHUNK_LOAD_DONE.get());
            InfsFarlands.LOGGER.info("FLSTAGE stage {}", stageLine());
        }

        anchorNanos = now;
        anchorSectionsLighted += sectionsLighted;
        anchorChunksLighted += chunksLighted;
        SECTIONS_IN.set(0L);
        lastLoadTotal = CHUNK_LOADS.get();
        SECTIONS_LIGHTED.set(0L);
        CHUNKS_LIGHTED.set(0L);
        for (int i = 0; i < STAGE_COUNT; i++) {
            STAGE_NANOS[i].set(0L);
            STAGE_SECTIONS[i].set(0L);
        }
    }

    /** 停服时清空计数与累计锚点。 */
    public static void clearWorldState() {
        SECTIONS_IN.set(0L);
        SECTIONS_LIGHTED.set(0L);
        CHUNKS_LIGHTED.set(0L);
        for (int i = 0; i < STAGE_COUNT; i++) {
            STAGE_NANOS[i].set(0L);
            STAGE_SECTIONS[i].set(0L);
        }
        anchorSectionsLighted = 0L;
        anchorChunksLighted = 0L;
        cumSeconds = 0.0;
        anchorNanos = System.nanoTime();
    }

    /** 两位小数。 */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
