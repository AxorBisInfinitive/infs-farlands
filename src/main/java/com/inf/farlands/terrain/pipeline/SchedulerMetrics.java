package com.inf.farlands.terrain.pipeline;

import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

/**
 * 调度器的周期读数，只报数，不改任何判据。每 200 tick 一行，由调度器在重扫那一拍调 {@link #flush()}。
 *
 * <p>字段含义：`emitted` 本窗发出的作业数；`blocked` 依赖未满足的次数；`repost` 失败重排次数；
 * `rejectTicket`、`rejectFace`、`rejectReady`、`rejectWindow` 分别对应准入四项拒绝；
 * `lightQuota`、`decoQuota`、`readbackQuota` 是被吸收的三处预算本窗发牌次数，用来看它们是否仍在发牌；
 * `faces` 当前面数。
 */
public final class SchedulerMetrics {

    private static final AtomicLong EMITTED = new AtomicLong();

    private static final AtomicLong BLOCKED = new AtomicLong();

    private static final AtomicLong REPOST = new AtomicLong();

    private static final AtomicLong REJECT_TICKET = new AtomicLong();

    private static final AtomicLong REJECT_FACE = new AtomicLong();

    private static final AtomicLong REJECT_READY = new AtomicLong();

    private static final AtomicLong REJECT_WINDOW = new AtomicLong();

    private static final AtomicLong LIGHT_QUOTA = new AtomicLong();

    private static final AtomicLong DECO_QUOTA = new AtomicLong();

    private static final AtomicLong READBACK_QUOTA = new AtomicLong();

    private SchedulerMetrics() {
    }

    public static void emitted() {
        EMITTED.incrementAndGet();
    }

    public static void blocked() {
        BLOCKED.incrementAndGet();
    }

    public static void repost() {
        REPOST.incrementAndGet();
    }

    public static void rejectTicket() {
        REJECT_TICKET.incrementAndGet();
    }

    public static void rejectFace() {
        REJECT_FACE.incrementAndGet();
    }

    public static void rejectReady() {
        REJECT_READY.incrementAndGet();
    }

    public static void rejectWindow() {
        REJECT_WINDOW.incrementAndGet();
    }

    public static void lightQuota() {
        LIGHT_QUOTA.incrementAndGet();
    }

    public static void decoQuota() {
        DECO_QUOTA.incrementAndGet();
    }

    public static void readbackQuota() {
        READBACK_QUOTA.incrementAndGet();
    }

    /** 打印并清零本窗计数。 */
    public static void flush() {
        InfsFarlands.LOGGER.info(
                "FLSCHED emitted={} blocked={} repost={} rejectTicket={} rejectFace={} rejectReady={} "
                        + "rejectWindow={} lightQuota={} decoQuota={} readbackQuota={} faces={}",
                EMITTED.getAndSet(0), BLOCKED.getAndSet(0), REPOST.getAndSet(0), REJECT_TICKET.getAndSet(0),
                REJECT_FACE.getAndSet(0), REJECT_READY.getAndSet(0), REJECT_WINDOW.getAndSet(0),
                LIGHT_QUOTA.getAndSet(0), DECO_QUOTA.getAndSet(0), READBACK_QUOTA.getAndSet(0),
                Scheduler.faces());
    }

    /** 停服清零。 */
    public static void clearWorldState() {
        EMITTED.set(0);
        BLOCKED.set(0);
        REPOST.set(0);
        REJECT_TICKET.set(0);
        REJECT_FACE.set(0);
        REJECT_READY.set(0);
        REJECT_WINDOW.set(0);
        LIGHT_QUOTA.set(0);
        DECO_QUOTA.set(0);
        READBACK_QUOTA.set(0);
    }

    /** 每 200 tick 调一次；开关在配置里，关掉时零日志。 */
    public static void flushIfEnabled() {
        if (FarlandsConfig.logScheduler) {
            flush();
        }
    }
}
