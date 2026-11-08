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

    private static final int SITES = 11;

    private static final String[] NAMES = { "prepare", "rebuild", "iterate", "ubo", "tick", "drain", "upload",
            "commit", "requeue", "submit", "draw" };

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

    public static void tick() {
        if (ENABLED) {
            ticks++;
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
        windowStartNanos = now;
    }
}
