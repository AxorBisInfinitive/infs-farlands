package com.inf.farlands.debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 诊断：档位条目的建立读数。临时仪器，取到读数后与同批探针一并删除。
 *
 * <p>只回答一件事：档位表里那些「窗口之外」或「容器里没有」的条目，是哪条路建出来的。造条目只有两个
 * 入口，一处是落档体，一处是状态取或建，所以标签用入口名加目标档就够反推调用点，不必取调用栈。
 *
 * <p>两栏判别力在 {@code inWin} 与 {@code inContainer}：前者为假即「推不动的带外条目」，后者为假即
 * 「装饰门看得见、流水线收不到」的条目。
 *
 * <p>逐条只打前若干条，桶计数全记，每 200 tick 由 {@code FarlandsTick} 打一行汇总。
 */
public final class StageSlotProbe {

    /** 逐条输出上限，只用来给形状。 */
    private static final int EVENT_LIMIT = 200;

    /** 建条目总次数。 */
    private static final AtomicLong CREATED = new AtomicLong();

    /** 其中窗口之外的次数。 */
    private static final AtomicLong OUT_OF_BAND = new AtomicLong();

    /** 其中容器里没有该段的次数。 */
    private static final AtomicLong NO_CONTAINER = new AtomicLong();

    /** 已输出的逐条行数。 */
    private static final AtomicLong EVENTS = new AtomicLong();

    /** 被清理回收的残留条目数。 */
    private static final AtomicLong RECLAIMED = new AtomicLong();

    /** 标签到次数。标签形如 `write:TERRAIN`、`restore:CARVERS`、`stateOf:dirty`。 */
    private static final Map<String, AtomicLong> TAGS = new ConcurrentHashMap<>();

    private StageSlotProbe() {
    }

    /** 一次条目建立。{@code tag} 标出入口与目标档。 */
    public static void created(LevelChunk chunk, int sectionY, String tag) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        CREATED.incrementAndGet();
        TAGS.computeIfAbsent(tag, k -> new AtomicLong()).incrementAndGet();
        Level level = chunk.getLevel();
        boolean inWindow = level != null && EntitySectionWindow.inAnyWindow(level.dimension(), sectionY);
        boolean inContainer = insideContainer(chunk, sectionY);
        if (!inWindow) {
            OUT_OF_BAND.incrementAndGet();
        }
        if (!inContainer) {
            NO_CONTAINER.incrementAndGet();
        }
        // 只在两栏有一栏为假时逐条打：两栏都真的条目是常态，打出来只是噪声。
        if (inWindow && inContainer) {
            return;
        }
        long events = EVENTS.incrementAndGet();
        if (events > EVENT_LIMIT) {
            return;
        }
        InfsFarlands.LOGGER.info("FLSLOT create chunk={},{} sy={} tag={} inWin={} inContainer={}",
                chunk.getPos().x(), chunk.getPos().z(), sectionY, tag, inWindow, inContainer);
    }

    /** 容器里有没有该段。构造期容器的字段可能还没就绪，取不到就按假记，宁可多打。 */
    private static boolean insideContainer(LevelChunk chunk, int sectionY) {
        try {
            return ((WindowedChunk) chunk).windowedAllSections().containsKey(sectionY);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 一次回收：清理删掉的残留条目数。 */
    public static void reclaimed(int n) {
        if (n > 0) {
            RECLAIMED.addAndGet(n);
        }
    }

    /** 打印并清零本窗计数。 */
    public static void flush() {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        StringBuilder tags = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, AtomicLong> e : TAGS.entrySet()) {
            long n = e.getValue().getAndSet(0L);
            if (n == 0L) {
                continue;
            }
            if (!first) {
                tags.append(' ');
            }
            tags.append(e.getKey()).append('=').append(n);
            first = false;
        }
        tags.append(']');
        InfsFarlands.LOGGER.info(
                "FLSLOT sum created={} outOfBand={} noContainer={} reclaimed={} events={} tags={}",
                CREATED.getAndSet(0L), OUT_OF_BAND.getAndSet(0L), NO_CONTAINER.getAndSet(0L),
                RECLAIMED.getAndSet(0L), EVENTS.getAndSet(0L), tags);
    }
}
