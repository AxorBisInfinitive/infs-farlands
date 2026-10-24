package com.inf.farlands.debug;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.pipeline.DependencyTable;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.pipeline.NeighborhoodTickets;
import com.inf.farlands.terrain.pipeline.OwnershipTable;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.terrain.structure.StructureDriver;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
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

    /** 心跳的最小间隔，单位毫秒。 */
    private static final long HEARTBEAT_MILLIS = 1000L;

    /**
     * 开始求值九格门的最小等待时长，单位毫秒。
     *
     * <p>前几秒壳还在造，289 格里大部分才刚建出 holder，九格必然不齐，那一栏没有判别力。门真正可能成立的
     * 时点是在 {@code ring} 到满之后，实测约 3 秒，取 5 秒留余量。低于这个时长时该栏写 {@code early}，
     * 与「求过值且成立」的 {@code ok} 分开。
     */
    private static final long GATE_MIN_WAITED_MILLIS = 5000L;

    /** 上次心跳的时刻。 */
    private static final AtomicLong LAST_BEAT = new AtomicLong();

    /**
     * 诊断：等待的那一格自己的装饰尝试、门拒与取锁成功三格计数。
     *
     * <p>全局的 {@code decoTry} 分不出这一点：盘面上别的 chunk 每秒被尝试几千次，而这一格可能一次都没被
     * 碰过。三个数只对栈顶那一格自增，所以「恒零」与「涨而门拒」分得开。
     */
    private static final AtomicLong WATCHED_TRY = new AtomicLong();

    private static final AtomicLong WATCHED_GATE_FAIL = new AtomicLong();

    private static final AtomicLong WATCHED_LOCK_OK = new AtomicLong();

    /** 上次心跳时读到存在流程累计数，用于算每秒新增。 */
    private static final AtomicLong LAST_LOADS = new AtomicLong();

    /** 一次取数请求。level 为空表示这次记录不参与判定。 */
    private static final class Entry {

        private final ServerLevel level;
        private final String dimension;
        private final int x;
        private final int z;
        private final ChunkStatus status;
        private final long startedAt = System.currentTimeMillis();
        private boolean reported;

        private Entry(ServerLevel level, int x, int z, ChunkStatus status) {
            this.level = level;
            this.dimension = level == null ? "?" : String.valueOf(level.dimension().identifier());
            this.x = x;
            this.z = z;
            this.status = status;
        }
    }

    /**
     * 该 chunk 是否就是当前等待栈顶那一格。
     *
     * <p>给生产侧的装饰尝试调用。只在主线程有意义，而装饰尝试本来就在主线程，所以不需要跨线程发布。
     * 判据取 level 实例加两个坐标，不比较维度 id，省掉一次字符串构造。
     */
    public static boolean isWatched(LevelChunk chunk) {
        if (chunk == null) {
            return false;
        }
        Entry entry = STACK.get().peek();
        return entry != null && entry.level == chunk.getLevel()
                && entry.x == chunk.getPos().x() && entry.z == chunk.getPos().z();
    }

    /** 等待的那一格被尝试提交装饰。 */
    public static void watchedTry() {
        WATCHED_TRY.incrementAndGet();
    }

    /** 等待的那一格被九格门拒。 */
    public static void watchedGateFail() {
        WATCHED_GATE_FAIL.incrementAndGet();
    }

    /** 等待的那一格取到了光照域锁。 */
    public static void watchedLockOk() {
        WATCHED_LOCK_OK.incrementAndGet();
    }

    /** 诊断：等待那一格的三格计数，累计值，不清零。 */
    public static String watchedSummary() {
        return "try=" + WATCHED_TRY.get()
                + " gate=" + WATCHED_GATE_FAIL.get()
                + " lock=" + WATCHED_LOCK_OK.get();
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
     * 等待期间每轮调用一次：栈顶那次取数若已超过阈值，先打一次现场，再按秒打心跳。
     *
     * <p>调用点在 {@code MainThreadExecutor.pollTask} 的 HEAD，也就是 {@code managedBlock} 每轮循环里。
     * 冻结期间这里仍在跑，而 {@code atEnd} 与泵都不在，所以它是唯一能报现场的钟。
     */
    public static void poll() {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        Deque<Entry> stack = STACK.get();
        Entry entry = stack.peek();
        if (entry == null || entry.level == null) {
            return;
        }
        long waited = System.currentTimeMillis() - entry.startedAt;
        if (waited < THRESHOLD_MILLIS) {
            return;
        }
        if (!entry.reported) {
            entry.reported = true;
            reportOnce(entry, waited, stack.size());
        }
        beat(entry, waited, stack.size());
    }

    /** 一次性明细：同一坐标只打一条，超过上限只打汇总。 */
    private static void reportOnce(Entry entry, long waited, int depth) {
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
        report(entry, waited, depth);
        dumpCells(entry);
    }

    /**
     * 一次性分类：中心 ±{@link StructureDriver#STRUCTURE_READ_RADIUS} 的 289 格各停在哪一档。
     *
     * <p>{@code ring} 只数 `LevelChunk`，所以它分不出五种形态，这一行就是补那五种：有 holder 没 chunk、
     * chunk 还是 ProtoChunk、有 LevelChunk 但完整档没跨、状态机没起、以及压根没有 holder。六栏相加应等于
     * 289，对不上就是遍历本身有问题。
     *
     * <p>一次冻结每个坐标只打一次。顺手取四个代表格打明细：最近、最远、以及前两个缺格。
     */
    private static void dumpCells(Entry entry) {
        int radius = StructureDriver.STRUCTURE_READ_RADIUS;
        int total = (radius * 2 + 1) * (radius * 2 + 1);
        int hold = 0;
        int noHold = 0;
        int noChunk = 0;
        int proto = 0;
        int levelChunks = 0;
        int noStatus = 0;
        int fsFull = 0;
        int fsBlock = 0;
        int fsEntity = 0;
        int fsInaccessible = 0;
        int cand = 0;
        int attGt0 = 0;
        int lvlMin = Integer.MAX_VALUE;
        int lvlMax = Integer.MIN_VALUE;
        int[] missA = { Integer.MIN_VALUE, 0 };
        int[] missB = { Integer.MIN_VALUE, 0 };
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkHolder holder = SectionLifecycle.latestHolder(entry.level, entry.x + dx, entry.z + dz);
                if (holder == null) {
                    noHold++;
                    if (missA[0] == Integer.MIN_VALUE) {
                        missA[0] = dx;
                        missA[1] = dz;
                    } else if (missB[0] == Integer.MIN_VALUE) {
                        missB[0] = dx;
                        missB[1] = dz;
                    }
                    continue;
                }
                hold++;
                int lvl = holder.getTicketLevel();
                if (lvl < lvlMin) {
                    lvlMin = lvl;
                }
                if (lvl > lvlMax) {
                    lvlMax = lvl;
                }
                switch (holder.getFullStatus()) {
                    case FULL -> fsFull++;
                    case BLOCK_TICKING -> fsBlock++;
                    case ENTITY_TICKING -> fsEntity++;
                    default -> fsInaccessible++;
                }
                if (holder.getLatestStatus() == null) {
                    noStatus++;
                }
                ChunkAccess latest = holder.getLatestChunk();
                if (latest == null) {
                    noChunk++;
                } else if (latest instanceof LevelChunk levelChunk) {
                    levelChunks++;
                    if (Scheduler.hasCandidate(levelChunk)) {
                        cand++;
                    }
                    if (Scheduler.candidateAttempts(levelChunk) > 0) {
                        attGt0++;
                    }
                } else {
                    proto++;
                }
            }
        }
        InfsFarlands.LOGGER.info(
                "FLCELL sum dim={} chunk={},{} ring={}/{} hold={} noHold={} noChunk={} proto={} lvlChunk={} "
                        + "noStatus={} fs{FULL={},BLOCK={},ENTITY={},INACC={}} lvl={},{} cand={} attGT0={}",
                entry.dimension, entry.x, entry.z, levelChunks, total, hold, noHold, noChunk, proto,
                levelChunks, noStatus, fsFull, fsBlock, fsEntity, fsInaccessible,
                lvlMin == Integer.MAX_VALUE ? -1 : lvlMin, lvlMax == Integer.MIN_VALUE ? -1 : lvlMax,
                cand, attGt0);
        spot(entry, 0, 0);
        spot(entry, radius, radius);
        if (missA[0] != Integer.MIN_VALUE) {
            spot(entry, missA[0], missA[1]);
        }
        if (missB[0] != Integer.MIN_VALUE) {
            spot(entry, missB[0], missB[1]);
        }
    }

    /** 一格的明细。取不到 holder 的几栏记 {@code -}。 */
    private static void spot(Entry entry, int dx, int dz) {
        int cx = entry.x + dx;
        int cz = entry.z + dz;
        ChunkHolder holder = SectionLifecycle.latestHolder(entry.level, cx, cz);
        if (holder == null) {
            InfsFarlands.LOGGER.info("FLCELL spot dim={} cell={}/{} hold=N", entry.dimension, dx, dz);
            return;
        }
        ChunkAccess latest = holder.getLatestChunk();
        String chunkKind = latest == null ? "none" : latest instanceof LevelChunk ? "level" : "proto";
        String candText = "-";
        String attText = "-";
        String readText = "-";
        String fullText = "-";
        if (latest instanceof LevelChunk levelChunk) {
            candText = String.valueOf(Scheduler.hasCandidate(levelChunk));
            attText = String.valueOf(Scheduler.candidateAttempts(levelChunk));
            readText = String.valueOf(SectionIO.isReadingAny(levelChunk));
            fullText = String.valueOf(ChunkCounters.isFullDone(levelChunk));
        }
        InfsFarlands.LOGGER.info(
                "FLCELL spot dim={} cell={}/{} hold=Y lvl={} fs={} ls={} chunk={} cand={} att={} read={} full={}",
                entry.dimension, dx, dz, holder.getTicketLevel(), holder.getFullStatus(),
                holder.getLatestStatus() == null ? "-" : holder.getLatestStatus().getName(),
                chunkKind, candText, attText, readText, fullText);
    }

    /**
     * 心跳：等待仍在继续时每秒一行，报「这一秒里有没有东西在动」。
     *
     * <p>五栏并排是为了分开五种互斥形态：{@code tickets} 不涨说明票没铺；{@code tickets} 涨而
     * {@code loads} 与 {@code ring} 不动说明票没传播成壳；{@code ring} 到满而 {@code att} 不动说明门没被
     * 重估；{@code ring} 停在中途且 {@code missing} 恒定说明有格永远进不来；{@code ring} 缓涨说明只是慢。
     *
     * <p>{@code ring} 是中心 ±{@link StructureDriver#STRUCTURE_READ_RADIUS} 那 289 格里有多少格已有
     * 壳，与 {@code DependencyTable.referencesBuildable} 的判据同源。
     *
     * <p>{@code gate} 与 {@code deco} 答另一个问题：这一格自己为什么等不到 FULL。{@code gate} 给九格里
     * 每一格当下的不成立原因，各带它的 {@code cand} 与 {@code claim}，逐秒比同一格的档位就能分开「在推进」
     * 与「没人推」；{@code deco} 是该格此刻是否被某次装饰认领，与 {@code gate} 并排可分开「门不过」与
     * 「门过得去但这一格正被占」。
     *
     * <p>{@code q} 是该维度的主线程执行器里排队未执行的任务数。它专治「收尾投了但没人抽」这一种：冻结期
     * 这个数长期大于零且不降，就是队列没被抽。它含该执行器上的一切任务，不只是我们的收尾，所以大于零只能
     * 证伪不能证成。
     *
     * <p>{@code lightOwner}、{@code lightActive} 与 {@code light} 三栏答「DECORATED 为什么不升 LIGHTED」：
     * {@code lightOwner} 是这一格的 LIGHTED 相认领编号，非零且不变就是被占；{@code lightActive} 是在飞的
     * 光照任务数，持续大于零而光池空闲就是没有出口；{@code light} 的五个计数之差各指出一处断点，见
     * {@code GenQueue.lightSummary()}。
     */
    private static void beat(Entry entry, long waited, int depth) {
        long now = System.currentTimeMillis();
        long last = LAST_BEAT.get();
        if (now - last < HEARTBEAT_MILLIS || !LAST_BEAT.compareAndSet(last, now)) {
            return;
        }
        ChunkPos pos = new ChunkPos(entry.x, entry.z);
        LevelChunk chunk = ChunkReadiness.chunkAt(entry.level, pos);
        if (chunk == null) {
            InfsFarlands.LOGGER.info("FLWAIT wait dim={} chunk={},{} waited={}ms depth={} chunk=none",
                    entry.dimension, entry.x, entry.z, waited, depth);
            return;
        }
        int radius = StructureDriver.STRUCTURE_READ_RADIUS;
        int total = (radius * 2 + 1) * (radius * 2 + 1);
        int present = 0;
        StringBuilder missing = new StringBuilder();
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(entry.level);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (cursor.at(entry.x + dx, entry.z + dz) != null) {
                    present++;
                } else if (missing.length() < 24) {
                    if (missing.length() > 0) {
                        missing.append(',');
                    }
                    missing.append(dx).append('/').append(dz);
                }
            }
        }
        long loads = StageMetrics.loadsTotal();
        long lastLoads = LAST_LOADS.getAndSet(loads);
        long loadDelta = lastLoads == 0L ? 0L : loads - lastLoads;
        // 九格门只在等待足够久之后才求值：更早的那几秒壳还没造齐，九格必然不成立。
        String gate = waited < GATE_MIN_WAITED_MILLIS ? "early" : DependencyTable.neighborGateAll(chunk);
        boolean deco = OwnershipTable.isChunkClaimed(OwnershipTable.DECORATED, pos.pack());
        InfsFarlands.LOGGER.info(
                "FLWAIT wait dim={} chunk={},{} waited={}ms depth={} ring={}/{} missing={} tickets={}/{} "
                        + "loads=+{} att={} cands={} hasCand={} stages={} gate={} deco={} lightOwner={} "
                        + "lightActive={} q={} locks=[{} {}] ticks=[{}] light=[{}] submits=[{}] watched=[{}]",
                entry.dimension, entry.x, entry.z, waited, depth, present, total,
                missing.length() == 0 ? "-" : missing.toString(),
                NeighborhoodTickets.adds(), NeighborhoodTickets.removes(), loadDelta,
                Scheduler.candidateAttempts(chunk), Scheduler.candidateCount(),
                Scheduler.hasCandidate(chunk), histogram(chunk), gate, deco,
                OwnershipTable.lightChunkClaimOwner(entry.x, entry.z), GenQueue.lightActive(),
                entry.level.getChunkSource().getPendingTasksCount(),
                DecorationFiller.lockSummary(), OwnershipTable.lockSummary(),
                Scheduler.tickSummary(), GenQueue.lightSummary(), Scheduler.submitSummary(),
                watchedSummary());
    }

    /** 档位直方图，下标即档位。 */
    private static String histogram(LevelChunk chunk) {
        int[] counts = new int[SectionStage.LIGHTED + 1];
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage >= 0 && stage < counts.length) {
                counts[stage]++;
            }
        });
        return java.util.Arrays.toString(counts);
    }

    /** 组装并打出那一行现场。取数全部走公开入口，缺 chunk 时只报能报的几栏。 */
    private static void report(Entry entry, long waited, int depth) {
        ChunkPos pos = new ChunkPos(entry.x, entry.z);
        LevelChunk chunk = ChunkReadiness.chunkAt(entry.level, pos);
        if (chunk == null) {
            InfsFarlands.LOGGER.info(
                    "FLSTUCK wait dim={} chunk={},{} status={} waited={}ms depth={} chunk=none",
                    entry.dimension, entry.x, entry.z, entry.status.getName(), waited, depth);
            return;
        }
        int[] sections = Scheduler.sectionsFor(chunk);
        int belowL = DependencyTable.pendingBelowIn(chunk, sections, SectionStage.LIGHTED);
        InfsFarlands.LOGGER.info(
                "FLSTUCK wait dim={} chunk={},{} status={} waited={}ms depth={} cand={} wrDone={} reading={} "
                        + "secs={} belowL={} light={} ter={} deco={} stages={} snap={} win={} sub={} exit={}",
                entry.dimension, entry.x, entry.z, entry.status.getName(), waited, depth,
                Scheduler.hasCandidate(chunk),
                ChunkCounters.isWindowReadDone(chunk),
                SectionIO.isReadingAny(chunk),
                sections.length,
                belowL,
                OwnershipTable.isLightedClaimed(entry.x, entry.z),
                OwnershipTable.isChunkClaimed(OwnershipTable.TERRAIN, pos.pack()),
                OwnershipTable.isChunkClaimed(OwnershipTable.DECORATED, pos.pack()),
                histogram(chunk),
                com.inf.farlands.util.window.PlayerSnapshot.dimensions(),
                com.inf.farlands.util.window.EntitySectionWindow.snapshot(),
                Scheduler.submitTrace(entry.dimension, pos.pack()),
                Scheduler.exitTrace(entry.dimension, pos.pack()));
    }
}
