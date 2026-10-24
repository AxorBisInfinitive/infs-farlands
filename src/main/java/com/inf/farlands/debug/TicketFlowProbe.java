package com.inf.farlands.debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

/**
 * 诊断：认领与票的流量计数。临时仪器，取到读数后与同批探针一并删除。
 *
 * <p>为什么按计数而不逐条：认领一次就加一张票，量级与作业数同阶，逐条打会把日志推到几百兆。
 * 这里只逐条留前 {@link #SAMPLE_LIMIT} 条做形状，其余进计数，每 {@link #SUMMARY_EVERY} 个事件汇总一行。
 *
 * <p>要回答的问题只有一个：加与撤是否配对。若 {@code claimOk} 与 {@code releaseOk} 相等而票层的
 * 加远多于撤，断点在撤票那一跳；若 {@code releaseOk} 远小于 {@code claimOk}，是认领格泄漏。
 */
public final class TicketFlowProbe {

    /** 逐条输出的上限，只用来给形状。 */
    private static final int SAMPLE_LIMIT = 100;

    /** 汇总间隔，按事件数。 */
    private static final long SUMMARY_EVERY = 50000L;

    /** 相名，下标与 {@code OwnershipTable} 的相号一致。 */
    private static final String[] PHASES = {
            "-", "BIOMES", "TERRAIN", "SURFACE", "CARVERS", "DECORATED", "LIGHTED"
    };

    private static final AtomicLong EVENTS = new AtomicLong();

    private static final AtomicLong[] CLAIM_ATTEMPT = counters(8);
    private static final AtomicLong[] CLAIM_OK = counters(8);
    private static final AtomicLong[] RELEASE_ATTEMPT = counters(8);
    private static final AtomicLong[] RELEASE_OK = counters(8);

    /** 票型名到加减次数。 */
    private static final Map<String, AtomicLong> TICKET_ADD = new ConcurrentHashMap<>();

    private static final Map<String, AtomicLong> TICKET_REMOVE = new ConcurrentHashMap<>();

    private TicketFlowProbe() {
    }

    private static AtomicLong[] counters(int size) {
        AtomicLong[] arr = new AtomicLong[size];
        for (int i = 0; i < size; i++) {
            arr[i] = new AtomicLong();
        }
        return arr;
    }

    /** 一次认领：{@code ok} 为 CAS 是否成功。 */
    public static void claim(int phase, long posKey, long owner, boolean ok) {
        if (!FarlandsConfig.logScheduler || phase <= 0 || phase >= PHASES.length) {
            return;
        }
        CLAIM_ATTEMPT[phase].incrementAndGet();
        if (ok) {
            CLAIM_OK[phase].incrementAndGet();
        }
        long events = EVENTS.incrementAndGet();
        if (events <= SAMPLE_LIMIT) {
            InfsFarlands.LOGGER.info("FLFLOW claim phase={} pos={} owner={} ok={}",
                    PHASES[phase], posKey, owner, ok);
        }
        summaryIfDue(events);
    }

    /** 一次释放：{@code ok} 为 CAS 是否成功。没成功就不撤票，正是要抓的那一支。 */
    public static void release(int phase, long posKey, long owner, boolean ok) {
        if (!FarlandsConfig.logScheduler || phase <= 0 || phase >= PHASES.length) {
            return;
        }
        RELEASE_ATTEMPT[phase].incrementAndGet();
        if (ok) {
            RELEASE_OK[phase].incrementAndGet();
        }
        long events = EVENTS.incrementAndGet();
        if (events <= SAMPLE_LIMIT) {
            InfsFarlands.LOGGER.info("FLFLOW release phase={} pos={} owner={} ok={}",
                    PHASES[phase], posKey, owner, ok);
        }
        summaryIfDue(events);
    }

    /** 票层的一次加或撤，按票型累计。前若干条打坐标与等级给形状。 */
    public static void ticket(boolean add, String typeName, int radius, int level, long posKey) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        (add ? TICKET_ADD : TICKET_REMOVE).computeIfAbsent(typeName, k -> new AtomicLong()).incrementAndGet();
        long events = EVENTS.incrementAndGet();
        if (events <= SAMPLE_LIMIT) {
            InfsFarlands.LOGGER.info("FLFLOW ticket {} type={} r={} lvl={} pos={}",
                    add ? "add" : "remove", typeName, radius, level, posKey);
        }
        summaryIfDue(events);
    }

    /** 到点就汇总：认领与释放两侧分相给数，票层按票型给数。 */
    private static void summaryIfDue(long events) {
        if (events % SUMMARY_EVERY != 0L) {
            return;
        }
        StringBuilder claim = new StringBuilder();
        StringBuilder release = new StringBuilder();
        for (int phase = 1; phase < PHASES.length; phase++) {
            if (CLAIM_ATTEMPT[phase].get() == 0L && RELEASE_ATTEMPT[phase].get() == 0L) {
                continue;
            }
            claim.append(PHASES[phase]).append('=').append(CLAIM_ATTEMPT[phase].get()).append('/')
                    .append(CLAIM_OK[phase].get()).append(' ');
            release.append(PHASES[phase]).append('=').append(RELEASE_ATTEMPT[phase].get()).append('/')
                    .append(RELEASE_OK[phase].get()).append(' ');
        }
        InfsFarlands.LOGGER.info("FLFLOW summary events={} claim(att/ok)={} release(att/ok)={}",
                events, claim, release);
        InfsFarlands.LOGGER.info("FLFLOW tickets add={} remove={}", TICKET_ADD, TICKET_REMOVE);
    }
}
