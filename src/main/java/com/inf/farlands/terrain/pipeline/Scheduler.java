package com.inf.farlands.terrain.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.debug.CandidateProbe;
import com.inf.farlands.debug.DispatchProbe;
import com.inf.farlands.debug.StageWriteProbe;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.PlayerSnapshot;
import com.inf.farlands.util.world.WorldBounds;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Beardifier;

/**
 * 调度器：候选表、力面、准入、派发、第七行与落档体。主线程单点。
 *
 * <p>候选表按身份记，落档体的扇出、入队入口、装饰与结构相的信号都往这里 submit。每 tick 一次
 * {@link #tick()}：按距离排序、逐候选准入，过了就交出去，装饰相与生成相各判自己的门，第七行成立就
 * 直接补 FULL。
 *
 * <p>力面：一个面是「某个维度里、半径 radius 的方阵、段范围 [minSection, maxSection]」，由出生区
 * 预加载与 Chunky 登记。准入的竖直判据读玩家窗口与力面的并集，因为这两个面都没有玩家。
 *
 * <p>候选的段范围只有一个来源，见 {@link #sectionsFor}：准入的欠账判据与 {@code GenTask} 的收集读
 * 同一份数组。两处各写一份来源会让准入放行而任务收不到段，那是每 tick 空转的直接来源。
 *
 * <p>准入被拒分两类：{@code WINDOW} 与 {@code FACE} 不重投，窗口滑动与力面登记会重新提交；其余
 * 暂时性原因按 {@link #RETRY_BACKOFF_MILLIS} 退避重投，次数上限 {@link #RETRY_MAX_ATTEMPTS}。
 * 重投用墙钟而不是 tick 计数：预加载与建世界期没有 tick，按 tick 记的退避永远不会到期。
 */
public final class Scheduler {

    /**
     * 一个驱动面。
     *
     * @param dimension  所属维度
     * @param centerX    中心 chunk 的 x
     * @param centerZ    中心 chunk 的 z
     * @param radius     驱动半径，chunk 数
     * @param minSection 段范围下界
     * @param maxSection 段范围上界
     */
    public record Face(ResourceKey<Level> dimension, int centerX, int centerZ, int radius, int minSection,
            int maxSection) {
    }

    private static final Map<ResourceKey<Level>, List<Face>> FACES = new ConcurrentHashMap<>();

    private Scheduler() {
    }

    /** 登记一个面。主线程调用。 */
    public static void register(Face face) {
        FACES.computeIfAbsent(face.dimension(), k -> new ArrayList<>()).add(face);
    }

    /** 撤销一个面。主线程调用。 */
    public static void unregister(Face face) {
        List<Face> list = FACES.get(face.dimension());
        if (list != null) {
            list.remove(face);
        }
    }

    /** 当前面数，供 `FLSCHED` 读数。 */
    public static int faces() {
        int n = 0;
        for (List<Face> list : FACES.values()) {
            n += list.size();
        }
        return n;
    }

    /** 停服清空。 */
    public static void clearWorldState() {
        FACES.clear();
        CANDIDATES.clear();
        REJECT_LOGGED.clear();
        FULL_LOGGED.clear();
        SUBMITS.clear();
        EXITS.clear();
        SUBMIT_TOTAL.set(0L);
        EXIT_TOTAL.set(0L);
        SEQ.set(0);
    }

    // ---- 候选与准入 ----

    /** 一个待办的 chunk。preloadSections 非空表示调用方给了显式范围，与面、窗口并集合并使用。 */
    private static final class Candidate {

        final LevelChunk chunk;
        final int[] preloadSections;
        final long seq;
        /** 被暂时性原因拒绝的次数，供退避取值与上限判定。 */
        int attempts;
        /** 最早可再次尝试的墙钟时刻。 */
        long dueAtMillis;

        Candidate(LevelChunk chunk, int[] preloadSections, long seq) {
            this.chunk = chunk;
            this.preloadSections = preloadSections;
            this.seq = seq;
        }
    }

    private static final Map<Long, Candidate> CANDIDATES = new ConcurrentHashMap<>();

    /** 诊断行的限流：同一维度、同一 chunk、同一原因只打一次。 */
    private static final Set<String> REJECT_LOGGED = ConcurrentHashMap.newKeySet();

    /** 第七行求值行的限流：同一 chunk 只打一次。 */
    private static final Set<Long> FULL_LOGGED = ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** 退避梯度，单位毫秒。超出长度后取最后一档。 */
    private static final long[] RETRY_BACKOFF_MILLIS = { 2L, 8L, 200L };

    /** 同一候选被暂时性原因拒绝的上限，超过即放弃，等下一次显式提交。 */
    private static final int RETRY_MAX_ATTEMPTS = 64;

    private static final int[] EMPTY_SECTIONS = new int[0];

    /** 该 chunk 的记账身份，0 表示客户端或 ProtoChunk。 */
    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof com.inf.farlands.serialize.CounterIdentity identity ? identity.counterId() : 0L;
    }

    /** 登记一个待办 chunk，段范围由 {@link #sectionsFor} 现算。 */
    public static void submit(LevelChunk chunk) {
        submit(chunk, null);
    }

    /**
     * 候选表里是否还有待派发的 chunk。
     *
     * <p>只给预 tick 期的泵用：那一段没有 tick 循环，泵要判断「管道是否已经清空」才能退出。
     * 有候选不代表有进展，退避中的候选也在这里；调用方要另配时间上限。
     */
    public static boolean hasPendingCandidates() {
        return !CANDIDATES.isEmpty();
    }

    /** 该 chunk 是否还在候选表里。诊断用，主线程。 */
    public static boolean hasCandidate(LevelChunk chunk) {
        long key = identityOf(chunk);
        return key != 0L && CANDIDATES.containsKey(key);
    }

    /** 派发重入闸：落档体的扇出会在派发内 submit，不能再递归进来。 */
    private static final ThreadLocal<Boolean> TICKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 诊断：提交台账。键是维度加坐标，值是 {次数, 最后一次的 tick}。
     *
     * <p>键里必须有维度：只按坐标记时，同一格在两个维度各有一次提交会互相覆盖，读出来的「最后一次
     * 提交」属于哪个维度分不出来。
     *
     * <p>用途是把「某个 chunk 到底有没有被提交过、最后一次是什么时候」压成两个整数，读数在需要的地方
     * 才取，避免逐次打印把日志再灌一遍。只在 {@code logScheduler} 打开时记账。
     */
    private static final Map<String, long[]> SUBMITS = new ConcurrentHashMap<>();

    /** 台账键：维度 id 加坐标。 */
    private static String traceKey(String dimension, long chunkKey) {
        return dimension + '|' + chunkKey;
    }

    /** 诊断：一次都没提交过时的读数。 */
    public static String submitTrace(String dimension, long chunkKey) {
        long[] entry = SUBMITS.get(traceKey(dimension, chunkKey));
        if (entry == null) {
            return "0@never";
        }
        return entry[0] + "@" + entry[1];
    }

    /** 诊断：提交与离表的累计次数，供阻塞期的心跳读差值。只在诊断开关打开时增长。 */
    private static final java.util.concurrent.atomic.AtomicLong SUBMIT_TOTAL =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong EXIT_TOTAL =
            new java.util.concurrent.atomic.AtomicLong();

    /** 累计提交次数。诊断用，主线程。 */
    public static long submittedTotal() {
        return SUBMIT_TOTAL.get();
    }

    /** 累计离表次数。诊断用，主线程。 */
    public static long exitedTotal() {
        return EXIT_TOTAL.get();
    }

    /** 候选表当前的条目数。诊断用，主线程。 */
    public static int candidateCount() {
        return CANDIDATES.size();
    }

    /** 该 chunk 当前候选已重投的次数；不在表里时返回 0。诊断用，主线程。 */
    public static int candidateAttempts(LevelChunk chunk) {
        long key = identityOf(chunk);
        if (key == 0L) {
            return 0;
        }
        Candidate candidate = CANDIDATES.get(key);
        return candidate == null ? 0 : candidate.attempts;
    }

    private static void recordSubmit(Level level, long chunkKey) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        SUBMIT_TOTAL.incrementAndGet();
        SUBMITS.compute(traceKey(dimensionOf(level), chunkKey), (key, entry) -> {
            long[] target = entry == null ? new long[2] : entry;
            target[0]++;
            target[1] = com.inf.farlands.FarlandsTick.getNow();
            return target;
        });
    }

    /** 台账里的维度名。取不到 level 时记一个占位，不猜。 */
    private static String dimensionOf(Level level) {
        return level == null ? "?" : String.valueOf(level.dimension().identifier());
    }

    // ---- 诊断：离表台账 ----
    //
    // 每条候选离表都记一条「原因 + tick」，只留最后一次。停摆的 chunk 一旦离表就不再进表，所以台账里
    // 留下的正是它最后一次离表的原因；这条原因不需要事先知道是哪个 chunk，也不需要逐次打印。

    /** 派发开头身份失配，条件删除旧条目。 */
    static final int EXIT_STALE = 0;

    /** 取不到 ServerLevel，理论不可达。 */
    static final int EXIT_NONLEVEL = 1;

    /** 可玩界外。 */
    static final int EXIT_FACE = 2;

    /** 距玩家太远。 */
    static final int EXIT_WINDOW = 3;

    /** 第七行成立，已补发 FULL。 */
    static final int EXIT_FULL = 4;

    /** 交给生成任务。 */
    static final int EXIT_GEN = 5;

    /** 交给生成任务时认领 CAS 失败，候选直接丢。 */
    static final int EXIT_GEN_SKIP = 6;

    private static final String[] EXIT_NAMES = {
            "STALE", "NONLEVEL", "FACE", "WINDOW", "FULL", "GEN", "GEN_SKIP" };

    /** 诊断：离表台账，键是维度加坐标，值是 {原因序号, tick}。 */
    private static final Map<String, long[]> EXITS = new ConcurrentHashMap<>();

    /** 诊断：最后一次离表的原因与 tick，形如 {@code GEN@82}；从未离表时是 {@code none}。 */
    public static String exitTrace(String dimension, long chunkKey) {
        long[] entry = EXITS.get(traceKey(dimension, chunkKey));
        if (entry == null) {
            return "none";
        }
        int tag = (int) entry[0];
        String name = tag >= 0 && tag < EXIT_NAMES.length ? EXIT_NAMES[tag] : String.valueOf(tag);
        return name + "@" + entry[1];
    }

    static void recordExit(Level level, long chunkKey, int tag) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        EXIT_TOTAL.incrementAndGet();
        long tick = com.inf.farlands.FarlandsTick.getNow();
        EXITS.compute(traceKey(dimensionOf(level), chunkKey), (key, entry) -> {
            long[] target = entry == null ? new long[2] : entry;
            target[0] = tag;
            target[1] = tick;
            return target;
        });
    }

    /**
     * 该维度有玩家而两张表里没有它时，就地补一次刷新。
     *
     * <p>准入的两道门读的是每 tick 刷新的快照与窗口并集，而它们只在 {@code FarlandsTick.atEnd} 刷新。
     * 阻塞取数发生在 tick 之内，atEnd 跑不到；这时提交进来的候选读到的是换维度之前的表，`WINDOW` 随即
     * 把它一次性地丢掉。刷新放在派发之前，判据与数据因此同源。
     *
     * <p>必须连票据一起刷，即调 {@code refreshTickState} 而不是 {@code refreshGates}。换维度之后该维度
     * 只有玩家自己那张票，票位 31 起、每格加一，到 FULL 那一档只覆盖两格；而结构引用相要的 ±8 是
     * 289 格，缺票时它们停在 {@code INACCESSIBLE}，永远不会被要求状态，也就永远不建壳。实测的形态是
     * 289 个 holder 里 218 个的 {@code getLatestStatus()} 为 null、票位 31 到 39。
     *
     * <p>加票本身只入队，传播由等待循环里每轮的 {@code pollTask} 调
     * {@code runDistanceManagerUpdates} 完成，所以这里不会同步跑出几百次存在流程。
     *
     * <p>主线程调用。判据为假时只付一次 `isEmpty` 与一次带缓存的分段查表。
     */
    private static void refreshIfMissing(ServerLevel level) {
        if (level.players().isEmpty()) {
            return;
        }
        if (EntitySectionWindow.ranges(level.dimension()).length != 0) {
            return;
        }
        net.minecraft.server.MinecraftServer server = level.getServer();
        if (server != null) {
            com.inf.farlands.FarlandsTick.refreshTickState(server);
        }
    }

    /** 诊断：提交次数按调用点分栏，累计值，不清零。 */
    private static final java.util.concurrent.atomic.AtomicLong SUB_ENQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_TASK =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_LIGHT =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_DECO =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_READ =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_EXIST =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_HOOK =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_STAGE =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SUB_READY =
            new java.util.concurrent.atomic.AtomicLong();

    /** 未带标签的提交次数。出现非零即说明有一处调用点没传标签。 */
    private static final java.util.concurrent.atomic.AtomicLong SUB_OTHER =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 诊断：提交次数按调用点分栏，累计值，不清零。
     *
     * <p>分栏的理由：提交本身会同步触发一次派发，而派发会放出作业，作业收尾又提交，于是「每秒多少次
     * 提交」这一个数里可能藏着放大环。九栏之和必须等于 {@link #submittedTotal()} 的增量；对不上就是有
     * 一处调用点没传标签，落到 {@code other}。
     */
    public static String submitSummary() {
        return "enq=" + SUB_ENQ.get()
                + " task=" + SUB_TASK.get()
                + " light=" + SUB_LIGHT.get()
                + " deco=" + SUB_DECO.get()
                + " read=" + SUB_READ.get()
                + " exist=" + SUB_EXIST.get()
                + " hook=" + SUB_HOOK.get()
                + " stage=" + SUB_STAGE.get()
                + " ready=" + SUB_READY.get()
                + " other=" + SUB_OTHER.get();
    }

    private static void countSubmit(String src) {
        switch (src) {
            case "enq" -> SUB_ENQ.incrementAndGet();
            case "task" -> SUB_TASK.incrementAndGet();
            case "light" -> SUB_LIGHT.incrementAndGet();
            case "deco" -> SUB_DECO.incrementAndGet();
            case "read" -> SUB_READ.incrementAndGet();
            case "exist" -> SUB_EXIST.incrementAndGet();
            case "hook" -> SUB_HOOK.incrementAndGet();
            case "stage" -> SUB_STAGE.incrementAndGet();
            case "ready" -> SUB_READY.incrementAndGet();
            default -> SUB_OTHER.incrementAndGet();
        }
    }

    /** 登记一个待办 chunk，显式范围只作为 {@link #sectionsFor} 的一支。入队即派发。 */
    public static void submit(LevelChunk chunk, int[] preloadSections) {
        submit("other", chunk, preloadSections);
    }

    /**
     * 同上，{@code src} 只用于诊断分栏。
     *
     * <p>九个调用点：{@code enq} 是 {@code GenQueue} 的三个入队入口，{@code task} 是作业与任务收尾，
     * {@code light} 是光照收尾，{@code deco} 是装饰的两处，{@code read} 是读回回调，{@code exist} 是
     * 存在流程与预加载，{@code hook} 是 {@code TerrainHooks.enqueueGen}，{@code stage} 是落档扇出与读回
     * 恢复，{@code ready} 是准入里现场建 Beardifier 之后的重投。
     */
    public static void submit(String src, LevelChunk chunk, int[] preloadSections) {
        countSubmit(src);
        long key = identityOf(chunk);
        if (key == 0L) {
            return;
        }
        recordSubmit(chunk.getLevel(), chunk.getPos().pack());
        CANDIDATES.put(key, new Candidate(chunk, preloadSections, SEQ.incrementAndGet()));
        // 入队即派发：createLevels 里 setInitialSpawn 的阻塞取数发生在 tick 之前，只登记会死锁。
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (SectionIO.isMainThread(level)) {
            if (!TICKING.get()) {
                refreshIfMissing(level);
                tick("submit");
            }
        } else {
            SectionIO.runOnMainThread(() -> {
                refreshIfMissing(level);
                tick("submit");
            }, level);
        }
    }

    /** 登记一个待办 chunk，{@code src} 只用于诊断分栏。 */
    public static void submit(String src, LevelChunk chunk) {
        submit(src, chunk, null);
    }

    /**
     * 只登记、不派发。供批量补提交用。
     *
     * <p>逐条走 {@link #submit} 会在主线程上每条触发一次全表快照与排序，补提交的规模到几百时那是平方
     * 成本。本入口与 {@code submit} 共用身份键、序号与提交台账，只省掉派发；调用方扫完之后由既有的一拍
     * {@link #tick()} 统一派发。
     */
    static void submitQuiet(LevelChunk chunk) {
        long key = identityOf(chunk);
        if (key == 0L) {
            return;
        }
        recordSubmit(chunk.getLevel(), chunk.getPos().pack());
        CANDIDATES.put(key, new Candidate(chunk, null, SEQ.incrementAndGet()));
    }

    /**
     * 延后一段时间再派发该 chunk：登记候选并把最早可尝试时刻推到 {@code delayMillis} 之后。
     *
     * <p>用途是「此刻办不成、但还会再试」的失败出口，例如装饰取不到光照域锁。直接 {@code submit}
     * 会把每一次失败立刻接成下一轮尝试，主线程与池子都烧在同一个 chunk 上。延迟的计时者是墙钟而不是
     * tick：建世界期与预加载期都没有 tick，按 tick 记的延迟永远不会到期。
     *
     * <p>不立即派发，因为还没有到期，派发只会跳过它。
     */
    public static void submitLater(LevelChunk chunk, long delayMillis) {
        if (delayMillis <= 0L) {
            submit(chunk);
            return;
        }
        long key = identityOf(chunk);
        if (key == 0L) {
            return;
        }
        Candidate candidate = new Candidate(chunk, null, SEQ.incrementAndGet());
        candidate.dueAtMillis = System.currentTimeMillis() + delayMillis;
        CANDIDATES.put(key, candidate);
    }

    /** 累计的派发进入次数，按调用点分栏。诊断用，不清零，速率由相邻两行相减得到。 */
    private static final java.util.concurrent.atomic.AtomicLong TICK_END =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong TICK_PUMP =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong TICK_CREATE =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong TICK_PRELOAD =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong TICK_SUBMIT =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong TICK_POLL =
            new java.util.concurrent.atomic.AtomicLong();

    /** 未带标签的进入次数。出现非零即说明有一处调用点没传标签。 */
    private static final java.util.concurrent.atomic.AtomicLong TICK_OTHER =
            new java.util.concurrent.atomic.AtomicLong();

    /** 被重入闸挡回的次数。与各栏之和的差就是「要求跑」与「真跑了」的差。 */
    private static final java.util.concurrent.atomic.AtomicLong TICK_REENTRANT =
            new java.util.concurrent.atomic.AtomicLong();

    /** 诊断：派发进入次数按调用点分栏，累计值，不清零。 */
    public static String tickSummary() {
        return "end=" + TICK_END.get()
                + " pump=" + TICK_PUMP.get()
                + " create=" + TICK_CREATE.get()
                + " preload=" + TICK_PRELOAD.get()
                + " submit=" + TICK_SUBMIT.get()
                + " poll=" + TICK_POLL.get()
                + " other=" + TICK_OTHER.get()
                + " reent=" + TICK_REENTRANT.get();
    }

    /** 每 tick 一次：按距离排序逐个准入，过了就交出去。 */
    public static void tick() {
        tick("other");
    }

    /**
     * 同上，{@code src} 只用于诊断分栏。
     *
     * <p>分栏的理由：冻结期「tick 没跑」与「tick 在跑但准入不放行」的其余读数完全一样，候选数与段位都
     * 不动。五个调用点是 {@code end}、{@code pump}、{@code poll}、{@code create}、{@code preload}，加
     * {@code submit}，各自代表一条驱动路径。其中 {@code poll} 是 chunk 执行器等本期的那一条，见
     * {@code ChunkCachePumpMixin}。
     */
    public static void tick(String src) {
        switch (src) {
            case "end" -> TICK_END.incrementAndGet();
            case "pump" -> TICK_PUMP.incrementAndGet();
            case "create" -> TICK_CREATE.incrementAndGet();
            case "preload" -> TICK_PRELOAD.incrementAndGet();
            case "submit" -> TICK_SUBMIT.incrementAndGet();
            case "poll" -> TICK_POLL.incrementAndGet();
            default -> TICK_OTHER.incrementAndGet();
        }
        if (TICKING.get()) {
            TICK_REENTRANT.incrementAndGet();
            return;
        }
        TICKING.set(Boolean.TRUE);
        try {
            dispatch();
        } finally {
            TICKING.set(Boolean.FALSE);
        }
    }

    private static void dispatch() {
        if (CANDIDATES.isEmpty()) {
            return;
        }
        int budget = FarlandsConfig.maxGenTasksPerTick;
        List<Map.Entry<Long, Candidate>> snapshot = new ArrayList<>(CANDIDATES.entrySet());
        snapshot.sort(Comparator.comparingLong(e -> distanceOf(e.getValue())));
        Map<ServerLevel, SectionLifecycle.ChunkCursor> cursors = new HashMap<>();
        for (Map.Entry<Long, Candidate> entry : snapshot) {
            if (budget <= 0) {
                break;
            }
            long key = entry.getKey();
            Candidate candidate = entry.getValue();
            LevelChunk chunk = candidate.chunk;
            // 同坐标的前一代已交卸载：身份与键不再相符，条目是死引用，清掉。
            if (identityOf(chunk) != key) {
                CANDIDATES.remove(key, candidate);
                CandidateProbe.left(chunk, "STALE", 1, true);
                recordExit(chunk.getLevel(), chunk.getPos().pack(), EXIT_STALE);
                continue;
            }
            long now = System.currentTimeMillis();
            if (now < candidate.dueAtMillis) {
                continue;
            }
            // 条件删除：快照之后新提交的同键候选不能被旧候选删掉。
            if (!CANDIDATES.remove(key, candidate)) {
                continue;
            }
            if (!(chunk.getLevel() instanceof ServerLevel level)) {
                CandidateProbe.left(chunk, "NONLEVEL", 1, true);
                recordExit(chunk.getLevel(), chunk.getPos().pack(), EXIT_NONLEVEL);
                continue;
            }
            ChunkPos pos = chunk.getPos();
            if (!WorldBounds.inChunkRange(pos.x(), pos.z())) {
                SchedulerMetrics.rejectFace();
                logReject(level, chunk, "FACE", "");
                CandidateProbe.left(chunk, "FACE", 1, true);
                recordExit(level, pos.pack(), EXIT_FACE);
                continue;
            }
            SectionLifecycle.ChunkCursor cursor = cursors.computeIfAbsent(level, SectionLifecycle::cursor);
            int ticketLevel = cursor.ticketLevelAt(pos.x(), pos.z());
            int allowed = DependencyTable.allowedStage(ticketLevel);
            if (allowed < SectionStage.TERRAIN) {
                SchedulerMetrics.rejectTicket();
                logReject(level, chunk, "TICKET", "ticket=" + ticketLevel + " allowed=" + allowed);
                requeue(key, candidate, "TICKET");
                continue;
            }
            if (!GenQueue.isNearPlayer(chunk)) {
                SchedulerMetrics.rejectWindow();
                // 该维度有玩家、窗口表里却没有它的带，说明刷新还没发生：此刻是「还不知道」，不是
                // 「确实太远」。丢掉等于把活永久丢掉，因为重投与重扫都不在这条路径上。判据取窗口表，
                // 因为准入的竖直面用它。两条出口都自解：刷新之后按正常路径准入，玩家离开之后判据转假，
                // 候选按原语义被丢弃。
                if (!level.players().isEmpty()
                        && EntitySectionWindow.ranges(level.dimension()).length == 0) {
                    logReject(level, chunk, "WINDOW-UNKNOWN", FarlandsConfig.logScheduler
                            ? "snap=" + PlayerSnapshot.dimensions() : "");
                    requeuePersistent(key, candidate);
                    continue;
                }
                // 确实太远：一次性移除，与上面「还不知道」那一支分开记原因。
                logReject(level, chunk, "WINDOW", FarlandsConfig.logScheduler
                        ? "snap=" + PlayerSnapshot.dimensions() : "");
                CandidateProbe.left(chunk, "WINDOW", 1, true);
                recordExit(level, pos.pack(), EXIT_WINDOW);
                continue;
            }
            int[] sections = sectionsFor(level, chunk, candidate.preloadSections);
            boolean wrDone = ChunkCounters.isWindowReadDone(chunk);
            // 第七行：本候选范围内每段都已点亮，且窗口面读过盘。范围为空不算完成：那是「没有可做的
            // 段」，不是「做完了」。FULL 只对「有人等」的 chunk 承诺，所以这一行排在面与窗口之后。
            if (wrDone && sections.length > 0
                    && DependencyTable.pendingBelowIn(chunk, sections, SectionStage.LIGHTED) == 0) {
                logFull(level, chunk);
                ChunkReadiness.completeFull(chunk);
                CandidateProbe.left(chunk, "FULL", 1, false);
                recordExit(level, pos.pack(), EXIT_FULL);
                continue;
            }
            // 生成支：范围内还有低于 CARVERS 的段。装饰支：本 chunk 已有段停在 CARVERS。两支分开判，
            // 因为段全到 CARVERS 之后生成侧无活可做，而装饰恰恰从那一刻开始。
            boolean gen = sections.length > 0
                    && DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS) > 0;
            boolean deco = anyStage(chunk, SectionStage.CARVERS);
            if (!gen && !deco) {
                // 光照支：整窗段都停在 DECORATED 时，缺口在「装饰已完成、光照没触发」这一档。这一档没有
                // 别的出口，LIGHTED 只能由 notifyGenerated 的认领触发一次，那一次没成就永远没人再来。
                // 所以它必须在这里被当成活：重触发，并按固定节奏留在候选表里，不消耗重投次数。
                if (anyStage(chunk, SectionStage.DECORATED)) {
                    GenQueue.notifyGenerated(chunk);
                    logReject(level, chunk, "LIGHT", "");
                    requeuePersistent(key, candidate);
                    continue;
                }
                SchedulerMetrics.blocked();
                logReject(level, chunk, "WORK", "");
                requeue(key, candidate, "WORK");
                continue;
            }
            if (deco && DecorationFiller.trySubmitDecoration(chunk)) {
                SchedulerMetrics.emitted();
                budget--;
                continue;
            }
            if (!gen) {
                // 装饰这一支同样不封顶：提交失败只有两条原因，九格门与光照域锁，两者都是瞬时争用，
                // 而退避到顶只要约十三秒。争用一旦超过这个窗口，候选会离表，此后没有周期性重扫把它
                // 放回来，段就永久停在 CARVERS。固定节奏重投把它变成有界等待。
                SchedulerMetrics.blocked();
                logReject(level, chunk, "DECO", "");
                requeuePersistent(key, candidate);
                continue;
            }
            // C 依赖：适配数据就位。未建时若 ±8 的壳齐就现场建引用相与 Beardifier，建完凭下一次提交重试。
            if (!((ChunkBeardifier) chunk).hasBeardifier()) {
                boolean enabled = StructureDriver.structuresEnabled(level);
                boolean inRange = StructureDriver.inStructureRange(chunk);
                boolean refs = enabled && inRange && StructureDriver.referencesBuildable(level, chunk);
                if (refs) {
                    StructureDriver.buildReferences(level, chunk);
                    ((ChunkBeardifier) chunk).setBeardifier(
                            Beardifier.forStructuresInChunk(level.structureManager(), chunk.getPos()));
                    submit("ready", chunk);
                } else {
                    SchedulerMetrics.rejectReady();
                    logReject(level, chunk, "READY", "structures=" + enabled + " inRange=" + inRange);
                    requeue(key, candidate, "READY");
                }
                continue;
            }
            SchedulerMetrics.emitted();
            budget--;
            CandidateProbe.left(chunk, "GEN", 1, false);
            recordExit(level, pos.pack(), EXIT_GEN);
            // 派发序号：作业入口那一行会带上同一个值，两边按序号对齐就能量出这一格被重派的节奏。
            long dispatchSeq = DispatchProbe.next(pos,
                    DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS), sections.length);
            GenQueue.enqueueTask(chunk, sections, dispatchSeq);
        }
    }

    /**
     * 本候选该处理的段：显式范围、所有覆盖该格的面、该维度的窗口并集的并集，再按可玩段界与在读状态
     * 过滤，升序去重。
     *
     * <p>准入的欠账判据与 {@code GenTask} 的收集必须读这一份。三支来源在旧写法里各自成判据，而收集侧
     * 只认窗口，于是面带里的段被准入当成欠账、任务又收不到，每 tick 空转一次。
     *
     * <p>过滤用 {@link WorldBounds#inSection} 而不是可表示段界：{@code AbstractTerrainFiller} 用
     * {@code minSection*16} 建 {@code NoiseSettings}，可表示界两端乘 16 已越过 int。
     *
     * <p>升序是前提：{@code GenTask.collectSegments} 只按 {@code sy == curMax+1} 判连续，乱序输入会把
     * 每次收集退化成一堆单段，重复输入会把同一段填两次。
     */
    /**
     * 不带显式范围的那一支：只取覆盖该格的面与窗口并集。事件点与退出读数用这条。
     *
     * <p>与带 {@code explicit} 的重载同一份实现。事件点手上没有候选，取到的范围与预加载期的显式范围
     * 等价：那时窗口为空，面就是范围。
     */
    public static int[] sectionsFor(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            return sectionsFor(level, chunk, null);
        }
        return EMPTY_SECTIONS;
    }

    static int[] sectionsFor(ServerLevel level, LevelChunk chunk, int[] explicit) {
        IntOpenHashSet set = new IntOpenHashSet();
        if (explicit != null) {
            for (int sy : explicit) {
                set.add(sy);
            }
        }
        List<Face> faces = FACES.get(level.dimension());
        if (faces != null) {
            ChunkPos pos = chunk.getPos();
            for (Face face : faces) {
                if (Math.max(Math.abs(face.centerX() - pos.x()), Math.abs(face.centerZ() - pos.z())) <= face.radius()) {
                    for (int sy = face.minSection(); sy <= face.maxSection(); sy++) {
                        set.add(sy);
                    }
                }
            }
        }
        EntitySectionWindow.forEachSectionInAnyWindow(level.dimension(), set::add);
        if (set.isEmpty()) {
            return EMPTY_SECTIONS;
        }
        int[] sorted = set.toIntArray();
        Arrays.sort(sorted);
        int n = 0;
        for (int i = 0; i < sorted.length; i++) {
            int sy = sorted[i];
            if (!WorldBounds.inSection(sy)) {
                continue;
            }
            // 读回会整体替换段对象，fill 与它重叠时写进旧对象的内容被丢弃，而高度图已被改过。
            if (SectionIO.isReading(chunk, sy)) {
                continue;
            }
            sorted[n++] = sy;
        }
        return n == sorted.length ? sorted : Arrays.copyOf(sorted, n);
    }

    /** 该 chunk 是否有恰好停在 stage 的段。装饰的判据，与依赖表的 CARVERS 行同源。 */
    private static boolean anyStage(LevelChunk chunk, int stage) {
        boolean[] found = { false };
        SectionStage.forEachStage(chunk, (sy, s) -> {
            if (s == stage) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 暂时性拒绝的重投：带退避，次数到顶即放弃，等下一次显式提交。
     *
     * <p>到顶那一次是静默丢活：候选离开表，除非法提交再没人管它。{@code exit} 只作读数，标出是被哪道
     * 门耗尽的。
     */
    private static void requeue(long key, Candidate candidate, String exit) {
        if (candidate.attempts >= RETRY_MAX_ATTEMPTS) {
            CandidateProbe.exhausted(candidate.chunk, exit, candidate.attempts);
            return;
        }
        candidate.attempts++;
        long backoff = RETRY_BACKOFF_MILLIS[Math.min(candidate.attempts - 1, RETRY_BACKOFF_MILLIS.length - 1)];
        candidate.dueAtMillis = System.currentTimeMillis() + backoff;
        CANDIDATES.put(key, candidate);
        SchedulerMetrics.repost();
    }

    /**
     * 光照与装饰两支的重投：固定一档退避，不递增次数，因此永不被丢。
     *
     * <p>与 {@link #requeue} 分开的理由是语义不同。那边的次数上限表达「暂时性原因，重试几下就放弃」，
     * 而停在 {@code DECORATED} 或装饰提交被门与锁挡回都不是可以放弃的状态：放弃等于永久停摆，所以它
     * 只能有节奏，不能有上限。
     */
    private static void requeuePersistent(long key, Candidate candidate) {
        candidate.dueAtMillis = System.currentTimeMillis()
                + RETRY_BACKOFF_MILLIS[RETRY_BACKOFF_MILLIS.length - 1];
        CANDIDATES.put(key, candidate);
        SchedulerMetrics.repost();
    }

    /** 距离用快照，避免逐候选遍历玩家。 */
    private static long distanceOf(Candidate candidate) {
        LevelChunk chunk = candidate.chunk;
        if (chunk.getLevel() instanceof ServerLevel level) {
            return PlayerSnapshot.nearestDistance(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
        }
        return Long.MAX_VALUE;
    }

    /** 第七行求值行的读数，同一 chunk 只打一次。 */
    private static void logFull(ServerLevel level, LevelChunk chunk) {
        long key = chunk.getPos().pack();
        if (!FarlandsConfig.logScheduler || !FULL_LOGGED.add(key)) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLFULL evald dim={} chunk={},{} wrDone={} claimed={} holder={}",
                level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(),
                ChunkCounters.isWindowReadDone(chunk), ChunkCounters.isFullDone(chunk),
                SectionLifecycle.latestHolder(level, chunk.getPos().x(), chunk.getPos().z()) != null);
    }

    /**
     * 诊断行：准入被拒的原因与四个前件。同一维度、同一 chunk、同一原因只打一次。
     *
     * <p>键里必须有维度：只按坐标记时，同一格在主世界打过一次，换维度之后同一格的另一条原因会被
     * 同一个键吞掉，而跨维度恰是调度的常态。
     */
    private static void logReject(ServerLevel level, LevelChunk chunk, String reason, String extra) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        String key = String.valueOf(level.dimension().identifier()) + '|' + chunk.getPos().pack() + '|' + reason;
        if (!REJECT_LOGGED.add(key)) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLBLOCK reject reason={} dim={} chunk={},{} {} below={} wrDone={} beard={} stages={}",
                reason, level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(), extra,
                SectionStage.hasBelowLighted(chunk), ChunkCounters.isWindowReadDone(chunk),
                ((ChunkBeardifier) chunk).hasBeardifier(), stageHistogram(chunk));
    }

    /** 该 chunk 的段位直方图，按档位计数。 */
    private static String stageHistogram(LevelChunk chunk) {
        int[] counts = new int[SectionStage.LIGHTED + 1];
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage >= 0 && stage < counts.length) {
                counts[stage]++;
            }
        });
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(counts[i]);
        }
        return sb.append(']').toString();
    }

    /** 该 chunk 落在哪个面的方阵内，返回该面的段带 `{min, max}`；不在任何面内返回 null。 */
    public static int[] sectionRangeAt(ServerLevel level, ChunkPos pos) {
        List<Face> faces = FACES.get(level.dimension());
        if (faces == null) {
            return null;
        }
        for (Face face : faces) {
            if (Math.max(Math.abs(face.centerX() - pos.x()), Math.abs(face.centerZ() - pos.z())) <= face.radius()) {
                return new int[] { face.minSection(), face.maxSection() };
            }
        }
        return null;
    }

    /** 该 chunk 是否落在某个面的方阵内。供 `isNearPlayer` 复合。 */
    static boolean inAnyFace(ServerLevel level, ChunkPos pos) {
        List<Face> faces = FACES.get(level.dimension());
        if (faces == null) {
            return false;
        }
        for (Face face : faces) {
            if (Math.max(Math.abs(face.centerX() - pos.x()), Math.abs(face.centerZ() - pos.z())) <= face.radius()) {
                return true;
            }
        }
        return false;
    }

    // ---- 落档体：档位与脏标记的唯一写入口 ----

    /**
     * 把一段集合的档位推到目标档。主线程上内联执行；池线程上 marshal 回主线程。
     *
     * <p>档位与脏标记只能由主线程写：`flags` 的读改写在池线程上会丢写，而 `stage` 的记账要与它成对。
     */
    public static void stage(LevelChunk chunk, List<Integer> sections, int targetStage) {
        int[] arr = new int[sections.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = sections.get(i);
        }
        stage(chunk, arr, targetStage);
    }

    /**
     * 把一段数组的档位推到目标档，语义同 {@link #stage(LevelChunk, List, int)}。
     *
     * <p>不转投主线程。档位所在的 {@link com.inf.farlands.serialize.SectionState} 字段是 volatile，
     * 读改写在段监视器内，派生记账的增减也在实例锁内，池线程直接写因此是安全的；而转投的代价是读者
     * 看到的是队列尾巴而不是写入结果，判据据此反复判定同一段欠账，重复投写、队列越积越长。
     *
     * <p>脏标记仍走 {@link #dirty} 的转投：它与编码池的清除之间需要串行，转投那一步是那条串行的载体。
     */
    public static void stage(LevelChunk chunk, int[] sections, int targetStage) {
        if (sections.length == 0) {
            return;
        }
        // 诊断：请求侧的段数，与真正走到 setStage 的次数相除，就能看出写有没有落地。
        StageWriteProbe.requested(sections.length);
        StageWriteProbe.direct();
        applyStage(chunk, sections, targetStage);
    }

    /** 读回专用的落档：无条件写，绕过单调闸。语义同 {@link #stage}，供盘上档低于内存档时使用。 */
    public static void restore(LevelChunk chunk, int[] sections, int targetStage) {
        if (sections.length == 0) {
            return;
        }
        StageWriteProbe.requested(sections.length);
        StageWriteProbe.direct();
        for (int sy : sections) {
            SectionStage.restoreStage(chunk, sy, targetStage);
        }
        submit("stage", chunk);
    }

    private static void applyStage(LevelChunk chunk, int[] sections, int targetStage) {
        for (int sy : sections) {
            SectionStage.setStage(chunk, sy, targetStage);
        }
        // 扇出：档位变了，下一相可能就绪，重新登记候选。候选被准入或拒绝后移除，不会自增长。
        submit("stage", chunk);
    }

    /** 标脏，语义同 {@link #stage(LevelChunk, int[], int)}。 */
    public static void dirty(LevelChunk chunk, int[] sections, boolean set) {
        if (sections.length == 0) {
            return;
        }
        if (chunk.getLevel() instanceof ServerLevel level) {
            if (!SectionIO.isMainThread(level)) {
                SectionIO.runOnMainThread(() -> applyDirty(chunk, sections, set), level);
                return;
            }
        }
        applyDirty(chunk, sections, set);
    }

    private static void applyDirty(LevelChunk chunk, int[] sections, boolean set) {
        for (int sy : sections) {
            if (set) {
                SectionStage.markDirty(chunk, sy);
            } else {
                SectionStage.clearDirty(chunk, sy);
            }
        }
    }
}
