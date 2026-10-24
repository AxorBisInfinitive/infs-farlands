package com.inf.farlands.debug;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.pipeline.DependencyTable;
import com.inf.farlands.terrain.pipeline.OwnershipTable;
import com.inf.farlands.terrain.pipeline.Scheduler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 诊断：阻塞式取 chunk 的现场读数。
 *
 * <p>主线程在 {@code ServerChunkCache.getChunk} 里等到 FULL 时，会在 {@code managedBlock} 的循环里反复
 * 抽任务。本类在进入与离开那里时记一笔，并在等待期每轮检查一次：等待超过阈值就把它卡住的那几句一次
 * 打出来，即候选表里有没有它、读回闩落没落、段集合多大、还有几段没点亮、三张认领表的状态与段位直方图。
 *
 * <p>那是四种互斥形态的分界：候选缺失、读回未落、面与窗口没覆盖、段停在装饰或光照。没有这一行，
 * 只能知道「在等 FULL」，不能知道在等哪一句。
 *
 * <p>只在 {@code logScheduler} 打开时输出；同一 chunk 只打一次现场，明细条数有上限，超出只打汇总。
 * 定位完成后与同批探针一并删除。
 */
public final class FreezeWatch {

    /** 超过这个毫秒数才算阻塞，而不是一次正常的缓存未命中。 */
    private static final long THRESHOLD_MILLIS = 200L;

    /** 现场明细的条数上限。 */
    private static final int LOG_LIMIT = 64;

    /** 已打过现场的 chunk 数，用于限流与汇总。 */
    private static final AtomicInteger LOGGED = new AtomicInteger();

    /** 超限之后被压掉的次数。 */
    private static final AtomicInteger WAIVED = new AtomicInteger();

    /** 已打过现场的 chunk 键。 */
    private static final Set<Long> REPORTED = ConcurrentHashMap.newKeySet();

    /** 一次取数请求。level 为空表示这次记录不参与判定。 */
    private static final class Entry {

        private final ServerLevel level;
        private final int x;
        private final int z;
        private final ChunkStatus status;
        private final long startedAt = System.currentTimeMillis();
        private boolean reported;

        private Entry(ServerLevel level, int x, int z, ChunkStatus status) {
            this.level = level;
            this.x = x;
            this.z = z;
            this.status = status;
        }
    }

    /**
     * 每次取数的嵌套栈。
     *
     * <p>必须是栈而不是单值：等待期间抽到的主线程任务本身也会取 chunk，那种嵌套是常态，单值会被里层
     * 覆盖，外层出栈时就对不上。
     */
    private static final ThreadLocal<Deque<Entry>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private FreezeWatch() {
    }

    /** 进入一次取数。 */
    public static void enter(ServerLevel level, int x, int z, ChunkStatus status) {
        STACK.get().push(new Entry(level, x, z, status));
    }

    /** 离开一次取数。异常路径不会走到这里，栈会留一层，属可接受残留。 */
    public static void exit() {
        Deque<Entry> stack = STACK.get();
        Entry entry = stack.poll();
        if (entry == null || !FarlandsConfig.logScheduler || entry.status == null) {
            return;
        }
        long waited = System.currentTimeMillis() - entry.startedAt;
        if (waited >= THRESHOLD_MILLIS) {
            InfsFarlands.LOGGER.info("FLSTUCK exit chunk={},{} status={} elapsed={}ms depth={}",
                    entry.x, entry.z, entry.status.getName(), waited, stack.size() + 1);
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    /**
     * 等待期间每轮调用一次：栈顶那次取数若已超过阈值且没打过，就把现场打一次。
     *
     * <p>调用点在 {@code MainThreadExecutor.pollTask} 的 HEAD，也就是 {@code managedBlock} 每轮循环里。
     */
    public static void poll() {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        Deque<Entry> stack = STACK.get();
        Entry entry = stack.peek();
        if (entry == null || entry.reported || entry.level == null) {
            return;
        }
        long waited = System.currentTimeMillis() - entry.startedAt;
        if (waited < THRESHOLD_MILLIS) {
            return;
        }
        entry.reported = true;
        long key = ChunkPos.pack(entry.x, entry.z);
        if (!REPORTED.add(key)) {
            return;
        }
        if (LOGGED.get() >= LOG_LIMIT) {
            if (WAIVED.incrementAndGet() % 256 == 0) {
                InfsFarlands.LOGGER.info("FLSTUCK summary logged={} waived={}", LOGGED.get(),
                        WAIVED.get());
            }
            return;
        }
        LOGGED.incrementAndGet();
        report(entry, waited, stack.size());
    }

    /** 组装并打出那一行现场。取数全部走公开入口，缺 chunk 时只报能报的几栏。 */
    private static void report(Entry entry, long waited, int depth) {
        ChunkPos pos = new ChunkPos(entry.x, entry.z);
        LevelChunk chunk = ChunkReadiness.chunkAt(entry.level, pos);
        if (chunk == null) {
            InfsFarlands.LOGGER.info(
                    "FLSTUCK wait chunk={},{} status={} waited={}ms depth={} chunk=none",
                    entry.x, entry.z, entry.status.getName(), waited, depth);
            return;
        }
        int[] sections = Scheduler.sectionsFor(chunk);
        int belowL = DependencyTable.pendingBelowIn(chunk, sections, SectionStage.LIGHTED);
        int[] histogram = new int[SectionStage.LIGHTED + 1];
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage >= 0 && stage < histogram.length) {
                histogram[stage]++;
            }
        });
        InfsFarlands.LOGGER.info(
                "FLSTUCK wait chunk={},{} status={} waited={}ms depth={} cand={} wrDone={} reading={} "
                        + "secs={} belowL={} light={} ter={} deco={} stages={}",
                entry.x, entry.z, entry.status.getName(), waited, depth,
                Scheduler.hasCandidate(chunk),
                ChunkCounters.isWindowReadDone(chunk),
                SectionIO.isReadingAny(chunk),
                sections.length,
                belowL,
                OwnershipTable.isLightedClaimed(entry.x, entry.z),
                OwnershipTable.isChunkClaimed(OwnershipTable.TERRAIN, pos.pack()),
                OwnershipTable.isChunkClaimed(OwnershipTable.DECORATED, pos.pack()),
                java.util.Arrays.toString(histogram));
    }
}
