package com.inf.farlands.debug;

import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionStage;

/**
 * 诊断：档位写入侧的流量。临时仪器，取到读数后与同批探针一并删除。
 *
 * <p>四个量分开四种形态：
 *
 * <ul>
 * <li>{@code direct} 是本窗直接执行的落档调用次数。落档体不再转投主线程，所以它应等于调用次数。</li>
 * <li>{@code req} 是请求写入的段数，{@code exe} 是真正走到落档体的次数。{@code queued} 是两者累计差，
 * 直写之后应恒为 0，非零即说明还有一条转投路径在跑。</li>
 * <li>{@code same} 是「新旧档相同」的次数。它与 {@code exe} 同阶时，写是落了，但重复请求太多。</li>
 * <li>{@code blocked} 是单调闸拦下的降档写次数。主路径应恒为 0，读回路径走单独入口。</li>
 * <li>{@code mat} 是旧档到目标档的七乘七矩阵，只打非零项。它分开三种重复：同档重投、地表重投、
 * 雕刻重投。</li>
 * </ul>
 *
 * <p>只打一行，每 200 tick 一次，挂在 {@code FarlandsTick} 的同档里，与 `FLSCHED`、`FLSTAGE` 同窗。
 */
public final class StageWriteProbe {

    /** 档位数，下标与 {@link SectionStage} 的常量同序。 */
    private static final int STAGES = SectionStage.LIGHTED + 1;

    /** 档位名，矩阵里用名字打，省得再对下标。 */
    private static final String[] STAGE_NAMES = {
            "UNPROCESSED", "BIOMES", "TERRAIN", "SURFACE", "CARVERS", "DECORATED", "LIGHTED"
    };

    /** 本窗落档体请求的段数。 */
    private static final AtomicLong REQUESTED = new AtomicLong();

    /** 本窗真正走到 setStage 的次数。 */
    private static final AtomicLong EXECUTED = new AtomicLong();

    /** 本窗新旧档相同的次数。 */
    private static final AtomicLong SAME = new AtomicLong();

    /** 本窗升档与降档次数。 */
    private static final AtomicLong UP = new AtomicLong();

    private static final AtomicLong DOWN = new AtomicLong();

    /** 本窗直接执行的落档调用次数。落档体已不转投主线程，只剩这一个口径。 */
    private static final AtomicLong DIRECT = new AtomicLong();

    /** 本窗被单调闸拦下的降档写次数。 */
    private static final AtomicLong BLOCKED_DOWN = new AtomicLong();

    /** 累计请求段数与累计执行次数。两者之差即队列深度，跨窗不清；落档体直写之后应恒为 0。 */
    private static final AtomicLong REQUESTED_TOTAL = new AtomicLong();

    private static final AtomicLong EXECUTED_TOTAL = new AtomicLong();

    /** 旧档到目标档的矩阵，只在本窗内累计。 */
    private static final AtomicLong[][] MATRIX = new AtomicLong[STAGES][STAGES];

    static {
        for (int from = 0; from < STAGES; from++) {
            for (int to = 0; to < STAGES; to++) {
                MATRIX[from][to] = new AtomicLong();
            }
        }
    }

    private StageWriteProbe() {
    }

    /** 落档体请求写入 n 段。 */
    public static void requested(int n) {
        if (n > 0) {
            REQUESTED.addAndGet(n);
            REQUESTED_TOTAL.addAndGet(n);
        }
    }

    /** 落档体直接执行了一次，不转投主线程。 */
    public static void direct() {
        DIRECT.incrementAndGet();
    }

    /** 单调闸拦下了一次降档写。主路径应恒为 0，读回路径外的非零值是调用点错误的判据。 */
    public static void blockedDown(int old, int target) {
        BLOCKED_DOWN.incrementAndGet();
    }

    /** 一次真实的档位写入，{@code old} 是写之前读到的档。 */
    public static void applied(int old, int target) {
        EXECUTED.incrementAndGet();
        EXECUTED_TOTAL.incrementAndGet();
        if (old == target) {
            SAME.incrementAndGet();
        } else if (old < target) {
            UP.incrementAndGet();
        } else {
            DOWN.incrementAndGet();
        }
        if (old >= 0 && old < STAGES && target >= 0 && target < STAGES) {
            MATRIX[old][target].incrementAndGet();
        }
    }

    /** 打印并清零本窗计数。队列深度是累计差，不清零。 */
    public static void flush() {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        StringBuilder matrix = new StringBuilder("[");
        boolean first = true;
        for (int from = 0; from < STAGES; from++) {
            for (int to = 0; to < STAGES; to++) {
                long n = MATRIX[from][to].getAndSet(0L);
                if (n == 0L) {
                    continue;
                }
                if (!first) {
                    matrix.append(' ');
                }
                matrix.append(STAGE_NAMES[from]).append('>').append(STAGE_NAMES[to])
                        .append('=').append(n);
                first = false;
            }
        }
        matrix.append(']');
        InfsFarlands.LOGGER.info(
                "FLWRITE direct={} req={} exe={} same={} up={} down={} blocked={} queued={} mat={}",
                DIRECT.getAndSet(0), REQUESTED.getAndSet(0), EXECUTED.getAndSet(0), SAME.getAndSet(0),
                UP.getAndSet(0), DOWN.getAndSet(0), BLOCKED_DOWN.getAndSet(0),
                REQUESTED_TOTAL.get() - EXECUTED_TOTAL.get(), matrix);
    }
}
