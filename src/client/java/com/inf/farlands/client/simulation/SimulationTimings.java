package com.inf.farlands.client.simulation;

import java.util.Arrays;
import java.util.Locale;

import com.inf.farlands.InfsFarlands;

/**
 * 临时探针，结案即删：逐帧累计各处耗时与计数，每 200 帧打一行。
 *
 * <p>
 * 只有计时段与计数，不做判读：一行里的每个字段都能指到一处调用点。单位统一微秒，形如 {@code avg/max}。
 *
 * <p>
 * 逐帧只做整数加法与一次 {@code nanoTime}，不加字符串、不装箱；格式化只在 flush 那一帧发生。
 */
public final class SimulationTimings {

    /** 探针总开关，结案即删。 */
    public static final boolean ENABLED = true;

    public static final int PREPARE = 0;

    public static final int REBUILD = 1;

    public static final int ITERATE = 2;

    public static final int UBO = 3;

    public static final int TICK = 4;

    public static final int DRAIN = 5;

    public static final int UPLOAD = 6;

    public static final int COMMIT = 7;

    public static final int REQUEUE = 8;

    public static final int SUBMIT = 9;

    public static final int DRAW = 10;

    /** 提取期整段：{@code LevelRenderer.extractLevel} 的头到尾。 */
    public static final int EXTRACT = 11;

    /** 世界命令提交：{@code LevelRenderer.renderLevel} 的头到尾。名字避开既有的 submit。 */
    public static final int RLEVEL = 12;

    /** 呈现与收尾：{@code RenderSystem.flipFrame} 的头到尾，内含 {@code presentFrame}。 */
    public static final int FLIP = 13;

    /** 世界提交之后的整段：{@code GameRenderer.renderLevel} 的头到尾，含手、屏幕效果与特征提交。 */
    public static final int GRENDER = 14;

    /** 延迟特征提交：{@code FeatureRenderDispatcher.renderAllFeatures}。 */
    public static final int FEATURES = 15;

    /**
     * 后台遮挡传播一轮的耗时。
     *
     * <p>
     * 采样点在 {@code SimulationVisibility.farlands$propagate}，跑在后台线程，所以它不是帧内成本：本段打印的
     * 平均值是把窗口内的传播总耗时摊到帧上，峰值才是单轮传播的时长。判的是「图更新跟不跟得上相机」。
     */
    public static final int PROPAGATE = 16;

    private static final int SITES = 17;

    private static final String[] NAMES = { "prepare", "rebuild", "iterate", "ubo", "tick", "drain", "upload",
            "commit", "requeue", "submit", "draw", "extract", "rlevel", "flip", "grender", "features",
            "propagate" };

    /** 一行覆盖的帧数。 */
    private static final int WINDOW_FRAMES = 200;

    private static final long[] SUM = new long[SITES];

    private static final long[] MAX = new long[SITES];

    private static int frames;

    private static int ticks;

    private static int cacheHits;

    private static int rebuilds;

    private static int uploads;

    private static long uploadBytes;

    private static int drawSum;

    private static int drawMax;

    private static long visibleSum;

    private static int visibleFrames;

    private static int pending;

    private static long lastFrameNanos;

    private static long windowStartNanos;

    /** {@code flipFrame} 的返回时刻，用于结算跨帧尾段。0 表示还没采到边界。 */
    private static long lastFlipEnd;

    private static int outsideFrames;

    private static long outsideSum;

    private static long outsideMax;

    /** {@code GameRenderer.renderLevel} 的返回时刻，用于结算尾段。0 表示还没采到边界。 */
    private static long lastRenderLevelEnd;

    private static int tailFrames;

    private static long tailSum;

    private static long tailMax;

    /** {@code GameRenderer.renderLevel} 返回时记下尾段起点。 */
    public static void renderLevelEnded() {
        if (ENABLED) {
            lastRenderLevelEnd = System.nanoTime();
        }
    }

    /** {@code flipFrame} 进入时结算尾段：从 renderLevel 返回到这一刻，覆盖 GUI、blitToScreen 与 tracy。 */
    public static void closeTail() {
        if (!ENABLED || lastRenderLevelEnd == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - lastRenderLevelEnd;
        lastRenderLevelEnd = 0L;
        tailFrames++;
        tailSum += elapsed;
        if (elapsed > tailMax) {
            tailMax = elapsed;
        }
    }

    /** {@code flipFrame} 返回时记下帧边界。 */
    public static void flipEnded() {
        if (ENABLED) {
            lastFlipEnd = System.nanoTime();
        }
    }

    /** 下一次提取开始时结算上一帧的尾段：从 flipFrame 返回到这一刻。 */
    public static void closeOutside() {
        if (!ENABLED || lastFlipEnd == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - lastFlipEnd;
        lastFlipEnd = 0L;
        outsideFrames++;
        outsideSum += elapsed;
        if (elapsed > outsideMax) {
            outsideMax = elapsed;
        }
    }

    private SimulationTimings() {
    }

    /** 开始一段计时，返回起点。关掉探针时返回 0，{@link #end} 会直接丢弃。 */
    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void end(int site, long startNanos) {
        if (!ENABLED || startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        SUM[site] += elapsed;
        if (elapsed > MAX[site]) {
            MAX[site] = elapsed;
        }
    }

    /** 遮挡传播真的重算了一次。 */
    public static void rebuild() {
        if (ENABLED) {
            rebuilds++;
        }
    }

    /** 渲染批次缓存是否命中，每帧一次。 */
    public static void cache(boolean hit) {
        if (ENABLED && hit) {
            cacheHits++;
        }
    }

    /** 一次顶点上传与它的字节数。 */
    public static void upload(int bytes) {
        if (!ENABLED) {
            return;
        }
        uploads++;
        uploadBytes += bytes;
    }

    /** 本帧提交的 draw 条数。 */
    public static void draws(int count) {
        if (!ENABLED) {
            return;
        }
        drawSum += count;
        if (count > drawMax) {
            drawMax = count;
        }
    }

    /** 本帧可见集大小。 */
    public static void visible(int count) {
        if (!ENABLED) {
            return;
        }
        visibleSum += count;
        visibleFrames++;
    }

    /** 当前待烘段数，每次 tick 覆盖。 */
    public static void pending(int count) {
        if (ENABLED) {
            pending = count;
        }
    }

    /**
     * 记一轮后台传播的耗时。由主线程在取走计数器时调，形参是窗口内累计值与单轮峰值。
     *
     * <p>
     * 走累计接口而不是 {@link #begin()}/{@link #end(int, long)} 一对，是因为开始与结束分处两个线程，
     * 计数只在该段自己的字段里加，主线程取走时再并进来。
     */
    public static void propagate(long sumNanos, long maxNanos) {
        if (!ENABLED) {
            return;
        }
        SUM[PROPAGATE] += sumNanos;
        if (maxNanos > MAX[PROPAGATE]) {
            MAX[PROPAGATE] = maxNanos;
        }
    }

    public static void tick() {
        if (ENABLED) {
            ticks++;
        }
    }

    /**
     * 探针状态：本线程是否正处在段 UBO 的写入选段内。
     *
     * <p>
     * {@code MappableRingBuffer} 也被别的 UBO 使用，围栏等待只统计我们自己那次写入。渲染线程独占，不需要 volatile。
     */
    private static boolean uniformWriting;

    private static int cbWaitFrames;

    private static long cbWaitSum;

    private static long cbWaitMax;

    private static long cbCalls;

    /** 进入段 UBO 写入段，随后由 {@link #exitUniforms()} 退出。 */
    public static void enterUniforms() {
        uniformWriting = true;
    }

    public static void exitUniforms() {
        uniformWriting = false;
    }

    /** 探针是否处在段 UBO 写入段内。 */
    public static boolean uniformWriting() {
        return uniformWriting;
    }

    /** 记一次被计数的 {@code currentBuffer} 调用。 */
    public static void cbCall() {
        if (ENABLED) {
            cbCalls++;
        }
    }

    /** 记一次围栏等待的时长，纳秒。 */
    public static void cbWait(long nanos) {
        if (!ENABLED) {
            return;
        }
        cbWaitFrames++;
        cbWaitSum += nanos;
        if (nanos > cbWaitMax) {
            cbWaitMax = nanos;
        }
    }

    /** 每帧末尾调一次：累计帧跨度，满 200 帧打一行并清零。 */
    public static void frame() {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            windowStartNanos = now;
            return;
        }
        lastFrameNanos = now;
        frames++;
        if (frames < WINDOW_FRAMES) {
            return;
        }
        long span = now - windowStartNanos;
        double seconds = span / 1.0e9;
        double frameMs = span / 1.0e6 / frames;
        StringBuilder line = new StringBuilder(320);
        line.append("FLTIME frames=").append(frames);
        line.append(" ticks=").append(ticks);
        line.append(" fps=").append(String.format(Locale.ROOT, "%.1f", frames / seconds));
        line.append(" frameMs=").append(String.format(Locale.ROOT, "%.2f", frameMs));
        line.append(" cache=").append(cacheHits).append('/').append(frames);
        line.append(" rebuilds=").append(rebuilds);
        line.append(" draws=").append(drawSum / Math.max(1, frames)).append('/').append(drawMax);
        line.append(" visible=").append(visibleSum / Math.max(1, visibleFrames));
        line.append(" uploads=").append(uploads);
        line.append(" uploadMB=").append(String.format(Locale.ROOT, "%.1f", uploadBytes / (1024.0 * 1024.0)));
        line.append(" pending=").append(pending);
        line.append(" cbframes=").append(cbWaitFrames);
        line.append(" cbwait=").append(cbWaitSum / 1000L / Math.max(1, frames)).append('/')
                .append(cbWaitMax / 1000L);
        line.append(" cbcalls=").append(cbCalls / Math.max(1, frames));
        line.append(" outside=").append(outsideSum / 1000L / Math.max(1, outsideFrames)).append('/')
                .append(outsideMax / 1000L);
        line.append(" tail=").append(tailSum / 1000L / Math.max(1, tailFrames)).append('/')
                .append(tailMax / 1000L);
        line.append(" sum=").append((SUM[EXTRACT] + SUM[GRENDER] + SUM[FLIP] + outsideSum + tailSum) / 1000L
                / Math.max(1, frames));
        for (int site = 0; site < SITES; site++) {
            line.append(' ').append(NAMES[site]).append('=');
            line.append(SUM[site] / 1000L / Math.max(1, frames)).append('/').append(MAX[site] / 1000L);
        }
        InfsFarlands.LOGGER.info(line.toString());
        Arrays.fill(SUM, 0L);
        Arrays.fill(MAX, 0L);
        frames = 0;
        ticks = 0;
        cacheHits = 0;
        rebuilds = 0;
        uploads = 0;
        uploadBytes = 0L;
        drawSum = 0;
        drawMax = 0;
        visibleSum = 0L;
        visibleFrames = 0;
        cbWaitFrames = 0;
        cbWaitSum = 0L;
        cbWaitMax = 0L;
        cbCalls = 0L;
        outsideFrames = 0;
        outsideSum = 0L;
        outsideMax = 0L;
        tailFrames = 0;
        tailSum = 0L;
        tailMax = 0L;
        windowStartNanos = now;
    }
}
