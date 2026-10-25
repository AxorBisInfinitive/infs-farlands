package com.inf.farlands.terrain.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.CounterIdentities;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.PlayerSnapshot;
import com.inf.farlands.util.world.WorldBounds;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Beardifier;

/**
 * 调度器：候选表、力面、准入、派发、第七行与落档体。主线程单点。
 *
 * <p>
 * 候选表按身份记，落档体的扇出、入队入口、装饰与结构相的信号都往这里 submit。每 tick 一次
 * {@link #tick()}：按距离排序、逐候选准入，过了就交出去，装饰相与生成相各判自己的门，第七行成立就
 * 直接补 FULL。
 *
 * <p>
 * 力面：一个面是「某个维度里、半径 radius 的方阵、段范围 [minSection, maxSection]」，由出生区
 * 预加载与 Chunky 登记。准入的竖直判据读玩家窗口与力面的并集，因为这两个面都没有玩家。
 *
 * <p>
 * 候选的段范围只有一个来源，见 {@link #sectionsFor}：准入的欠账判据与 {@code GenTask} 的收集读
 * 同一份数组。两处各写一份来源会让准入放行而任务收不到段，那是每 tick 空转的直接来源。
 *
 * <p>
 * 准入被拒分两类：{@code WINDOW} 与 {@code FACE} 不重投，窗口滑动与力面登记会重新提交；其余
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

    /** 停服清空。 */
    public static void clearWorldState() {
        FACES.clear();
        CANDIDATES.clear();
        // 等待记录连同它铺的票一起丢：票随 level 消亡，这里只清内存条目。
        WAITED.clear();
        pendingLevel = null;
        inPump = false;
        // 窗口状态跟着清：换世界后第一次派发必须重新开窗，而不是继承上一个世界的余额与起点。
        budgetWindowStartMillis = 0L;
        budgetLeft = 0;
    }

    // ---- 候选与准入 ----

    /** 一个待办的 chunk。preloadSections 非空表示调用方给了显式范围，与面、窗口并集合并使用。 */
    private static final class Candidate {

        final LevelChunk chunk;
        final int[] preloadSections;
        /** 被暂时性原因拒绝的次数，供退避取值与上限判定。 */
        int attempts;
        /** 最早可再次尝试的墙钟时刻。 */
        long dueAtMillis;

        Candidate(LevelChunk chunk, int[] preloadSections) {
            this.chunk = chunk;
            this.preloadSections = preloadSections;
        }
    }

    private static final Map<Long, Candidate> CANDIDATES = new ConcurrentHashMap<>();

    // ---- 正被阻塞取数等着的那一格 ----

    /**
     * 一条等待记录。
     *
     * <p>{@code pinned} 记的是「结构票是不是本类铺的」：{@code NeighborhoodTickets.hold} 对同一个中心只让
     * 第一个持有方拿到真，撤票也因此只能由铺票的那一方做，否则会把别人的票撤掉。
     */
    private static final class Waited {

        private final ServerLevel level;

        private final ChunkPos pos;

        private long deadlineMillis;

        private boolean pinned;

        Waited(ServerLevel level, ChunkPos pos, long deadlineMillis) {
            this.level = level;
            this.pos = pos;
            this.deadlineMillis = deadlineMillis;
        }
    }

    /** 等待记录的期限。一次窗口带生成以秒计，五分钟是兜底，不是预期时长。 */
    private static final long WAITED_TTL_MILLIS = 300_000L;

    /** 从「即将阻塞」到「确认在阻塞」的门槛。泵只在等待期间运行，过了这个时长才登记。 */
    private static final long WAIT_CONFIRM_MILLIS = 2_000L;

    /** 维度的一次取数与坐标到等待记录。 */
    private static final Long2ObjectOpenHashMap<Waited> WAITED = new Long2ObjectOpenHashMap<>();

    /** 最近一次会阻塞的 FULL 取数。只在空位时写，提升后清空，所以同一时刻只跟一条等待。 */
    private static ServerLevel pendingLevel;

    private static int pendingChunkX;

    private static int pendingChunkZ;

    private static long pendingSinceMillis;

    /** 泵体执行中。为真时泵自身引起的阻塞取数不占位，否则会把外层真正冻结的那一格覆盖掉。 */
    private static boolean inPump;

    /** 键：维度的一次取数与坐标。同坐标在两个维度里互不相干。 */
    private static long keyOf(ServerLevel level, int chunkX, int chunkZ) {
        return ((long) System.identityHashCode(level.getChunkSource().chunkMap) << 32)
                ^ ChunkPos.pack(chunkX, chunkZ);
    }

    /**
     * 记下一次会阻塞的 FULL 取数。由 {@code ServerChunkCache.getChunk} 调 {@code managedBlock} 之前调。
     *
     * <p>这里只占位，不登记也不铺票：绝大多数 FULL 取数会立即返回，在这里做事等于给每一格都铺一张结构票。
     * 真正的登记与提交由泵在等待持续超过门槛之后做一次。
     *
     * <p>只在空位时写。泵体会反向取数，空位规则保证外层那一格不被它覆盖；提升清空之后，嵌在那里的等待
     * 才轮到自己占位。
     */
    public static void noteBlockingFullGet(ServerLevel level, int chunkX, int chunkZ) {
        if (inPump || pendingLevel != null) {
            return;
        }
        pendingLevel = level;
        pendingChunkX = chunkX;
        pendingChunkZ = chunkZ;
        pendingSinceMillis = System.currentTimeMillis();
    }

    /** 进入泵体。 */
    public static void beginPump() {
        inPump = true;
    }

    /** 退出泵体。必须与 {@link #beginPump()} 成对，走 finally。 */
    public static void endPump() {
        inPump = false;
    }

    /**
     * 一次阻塞取数返回时清掉占位。只在占位仍是这一格时清，嵌套取数不会误清外层。
     *
     * <p>没有这一步，任何一次没过门槛的短等待都会把占位永久占住，此后的等待再也登记不进来。
     */
    public static void clearPendingWait(ServerLevel level, int chunkX, int chunkZ) {
        if (pendingLevel != level || pendingChunkX != chunkX || pendingChunkZ != chunkZ) {
            return;
        }
        pendingLevel = null;
    }

    /**
     * 把已持续阻塞的取数提升为等待记录，并在壳在场时把它放回候选表。由泵每轮调一次。
     *
     * <p>提交是必需的：那一格可能刚因为太远被一次性移除，而等待者阻塞在 future 上，不会再有人显式提交它。
     */
    public static void promotePendingWait() {
        ServerLevel level = pendingLevel;
        if (level == null) {
            return;
        }
        if (System.currentTimeMillis() - pendingSinceMillis < WAIT_CONFIRM_MILLIS) {
            return;
        }
        int chunkX = pendingChunkX;
        int chunkZ = pendingChunkZ;
        pendingLevel = null;
        sweepExpiredWaiters();
        long key = keyOf(level, chunkX, chunkZ);
        long deadline = System.currentTimeMillis() + WAITED_TTL_MILLIS;
        Waited waited = WAITED.get(key);
        if (waited != null) {
            waited.deadlineMillis = deadline;
        } else {
            WAITED.put(key, new Waited(level, new ChunkPos(chunkX, chunkZ), deadline));
        }
        LevelChunk chunk = SectionLifecycle.latestChunk(level, chunkX, chunkZ);
        if (chunk != null) {
            submit(chunk);
        }
    }

    /**
     * 给被等待的格铺一张结构票。
     *
     * <p>只在派发发现结构门过不去时调。铺在置标记处会让每一次立即返回的 FULL 取数都铺一张，票会泛滥；
     * 走到这一支的格才真的需要 ±8 的壳。
     */
    private static void pinWaited(ServerLevel level, int chunkX, int chunkZ) {
        Waited waited = WAITED.get(keyOf(level, chunkX, chunkZ));
        if (waited == null || waited.pinned) {
            return;
        }
        waited.pinned = NeighborhoodTickets.hold(level, new ChunkPos(chunkX, chunkZ));
    }

    /** 撤掉等待记录。结构票是本类铺的才撤，别人的票不碰。 */
    public static void clearWaited(ServerLevel level, int chunkX, int chunkZ) {
        Waited waited = WAITED.remove(keyOf(level, chunkX, chunkZ));
        if (waited != null && waited.pinned) {
            NeighborhoodTickets.release(waited.level, waited.pos);
        }
    }

    /** 该格是否正被等待且未过期。到期即视为不再等待，记录留给清扫。 */
    private static boolean isWaited(ServerLevel level, int chunkX, int chunkZ) {
        Waited waited = WAITED.get(keyOf(level, chunkX, chunkZ));
        return waited != null && System.currentTimeMillis() <= waited.deadlineMillis;
    }

    /** 清过期记录，并把它们铺的票撤掉。票不撤会让那一圈壳永久保加载。 */
    private static void sweepExpiredWaiters() {
        if (WAITED.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Long> expired = null;
        for (Long2ObjectMap.Entry<Waited> entry : WAITED.long2ObjectEntrySet()) {
            Waited waited = entry.getValue();
            if (waited != null && now > waited.deadlineMillis) {
                if (expired == null) {
                    expired = new ArrayList<>();
                }
                expired.add(entry.getLongKey());
            }
        }
        if (expired == null) {
            return;
        }
        for (Long key : expired) {
            Waited waited = WAITED.remove(key.longValue());
            if (waited != null && waited.pinned) {
                NeighborhoodTickets.release(waited.level, waited.pos);
            }
        }
    }

    /** 退避梯度，单位毫秒。超出长度后取最后一档。 */
    private static final long[] RETRY_BACKOFF_MILLIS = { 2L, 8L, 200L };

    /** 同一候选被暂时性原因拒绝的上限，超过即放弃，等下一次显式提交。 */
    private static final int RETRY_MAX_ATTEMPTS = 64;

    /** 预算窗口长度，毫秒。与 maxLightTasksPerTick 那一族「每 50 毫秒」的口径同源。 */
    private static final long BUDGET_WINDOW_MILLIS = 50L;

    /** 当前窗口的起点，毫秒墙钟。 */
    private static long budgetWindowStartMillis;

    /**
     * 当前窗口剩余的候选考察额度，单位是「看过几个候选」。
     *
     * <p>计费点在派发循环的顶部，不在提交成功处：一次派发只要走到某个候选身上就付出成本，无论它最终
     * 被接受还是被拒。只计成功提交时，全被拒的状态下额度不降，早退永不触发，于是每一次 submit 都付一遍
     * 全表快照与逐候选取段，读数上表现为服务端线程持续九成单核。
     *
     * <p>两个字段只在服务端主线程读写：{@code dispatch} 只由 {@link #tick()} 调用，而 tick 的入口是
     * 服务端 tick、两个等待泵与建世界期的预加载循环，前两者各自先判 runningThread；{@code submit} 在
     * 非主线程上一律 marshal 回主线程。因此不加锁也不需要 volatile，但新增 tick 入口时必须守住这条。
     */
    private static int budgetLeft;

    private static final int[] EMPTY_SECTIONS = new int[0];

    /**
     * 开窗：距上次开窗满一个窗口就重置额度。
     *
     * <p>用墙钟而不是 tick：预加载与建世界期没有 tick，而那两个等待泵也在主线程上推进调度器，窗口制是
     * 把 tick、泵与 submit 三条来源折进同一个刻度的唯一办法。
     *
     * <p>时钟回拨按过期处理。差值为负时若只判「未满一个窗口」，额度会永远停在 0，派发就此停摆。
     */
    private static void openBudgetWindow() {
        long now = System.currentTimeMillis();
        if (now < budgetWindowStartMillis || now - budgetWindowStartMillis >= BUDGET_WINDOW_MILLIS) {
            budgetWindowStartMillis = now;
            budgetLeft = FarlandsConfig.maxGenTasksPerTick;
        }
    }

    /** 该 chunk 的记账身份，0 表示客户端或 ProtoChunk。 */
    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof com.inf.farlands.serialize.CounterIdentity identity ? identity.counterId() : 0L;
    }

    /** 登记一个待办 chunk，段范围由 {@link #sectionsFor} 现算。 */
    public static void submit(LevelChunk chunk) {
        submit(chunk, null);
    }

    /** 该 chunk 是否还在候选表里。供兜底重扫判重用，主线程。 */
    public static boolean hasCandidate(LevelChunk chunk) {
        long key = identityOf(chunk);
        return key != 0L && CANDIDATES.containsKey(key);
    }

    /** 派发重入闸：落档体的扇出会在派发内 submit，不能再递归进来。 */
    private static final ThreadLocal<Boolean> TICKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 该维度有玩家而两张表里没有它时，就地补一次刷新。
     *
     * <p>
     * 准入的两道门读的是每 tick 刷新的快照与窗口并集，而它们只在 {@code FarlandsTick.atEnd} 刷新。
     * 阻塞取数发生在 tick 之内，atEnd 跑不到；这时提交进来的候选读到的是换维度之前的表，`WINDOW` 随即
     * 把它一次性地丢掉。刷新放在派发之前，判据与数据因此同源。
     *
     * <p>
     * 必须连票据一起刷，即调 {@code refreshTickState} 而不是 {@code refreshGates}。换维度之后该维度
     * 只有玩家自己那张票，票位 31 起、每格加一，到 FULL 那一档只覆盖两格；而结构引用相要的 ±8 是
     * 289 格，缺票时它们停在 {@code INACCESSIBLE}，永远不会被要求状态，也就永远不建壳。实测的形态是
     * 289 个 holder 里 218 个的 {@code getLatestStatus()} 为 null、票位 31 到 39。
     *
     * <p>
     * 加票本身只入队，传播由等待循环里每轮的 {@code pollTask} 调
     * {@code runDistanceManagerUpdates} 完成，所以这里不会同步跑出几百次存在流程。
     *
     * <p>
     * 主线程调用。判据为假时只付一次 `isEmpty` 与一次带缓存的分段查表。
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

    /** 登记一个待办 chunk，显式范围只作为 {@link #sectionsFor} 的一支。入队即派发。 */
    public static void submit(LevelChunk chunk, int[] preloadSections) {
        // 关服期只写数据、不再登记与派发，与两处调度泵同规。这里是必须的一条：卸载每个 chunk 都会经装饰
        // 的九格门带进最多九次提交，而关服期那些派发没有消费者，实测把退出窗口从数秒拉到十五秒。
        if (ChunkReadiness.isShuttingDown()) {
            return;
        }
        long key = identityOf(chunk);
        if (key == 0L) {
            return;
        }
        // 候选表只装活身份：死身份的段在阶段表里读取恒为未处理、写入整条空转，进了表就会被判成整窗待
        // 生成，每一轮交出一个 fill 而档位永不前进。
        if (!CounterIdentities.isLive(key)) {
            StageMetrics.deadSubmitDropped();
            return;
        }
        CANDIDATES.put(key, new Candidate(chunk, preloadSections));
        // 入队即派发：createLevels 里 setInitialSpawn 的阻塞取数发生在 tick 之前，只登记会死锁。
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (SectionIO.isMainThread(level)) {
            if (!TICKING.get()) {
                refreshIfMissing(level);
                tick();
            }
        } else {
            SectionIO.runOnMainThread(() -> {
                refreshIfMissing(level);
                tick();
            }, level);
        }
    }

    /**
     * 只登记、不派发。供批量补提交用。
     *
     * <p>
     * 逐条走 {@link #submit} 会在主线程上每条触发一次全表快照与排序，补提交的规模到几百时那是平方
     * 成本。本入口与 {@code submit} 共用身份键、序号与提交台账，只省掉派发；调用方扫完之后由既有的一拍
     * {@link #tick()} 统一派发。
     */
    static void submitQuiet(LevelChunk chunk) {
        long key = identityOf(chunk);
        if (key == 0L) {
            return;
        }
        if (!CounterIdentities.isLive(key)) {
            StageMetrics.deadSubmitDropped();
            return;
        }
        CANDIDATES.put(key, new Candidate(chunk, null));
    }

    /**
     * 延后一段时间再派发该 chunk：登记候选并把最早可尝试时刻推到 {@code delayMillis} 之后。
     *
     * <p>
     * 用途是「此刻办不成、但还会再试」的失败出口，例如装饰取不到光照域锁。直接 {@code submit}
     * 会把每一次失败立刻接成下一轮尝试，主线程与池子都烧在同一个 chunk 上。延迟的计时者是墙钟而不是
     * tick：建世界期与预加载期都没有 tick，按 tick 记的延迟永远不会到期。
     *
     * <p>
     * 不立即派发，因为还没有到期，派发只会跳过它。
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
        if (!CounterIdentities.isLive(key)) {
            StageMetrics.deadSubmitDropped();
            return;
        }
        Candidate candidate = new Candidate(chunk, null);
        candidate.dueAtMillis = System.currentTimeMillis() + delayMillis;
        CANDIDATES.put(key, candidate);
    }

    /** 每 tick 一次：按距离排序逐个准入，过了就交出去。 */
    public static void tick() {
        if (TICKING.get()) {
            return;
        }
        TICKING.set(Boolean.TRUE);
        try {
            dispatch();
        } finally {
            TICKING.set(Boolean.FALSE);
        }
    }

    /**
     * 派发一次：按距离排序逐个准入，本窗口的额度用完就停。
     *
     * <p>额度是<b>跨派发共享</b>的，见 {@link #openBudgetWindow}：同一窗口内由 tick、两个等待泵与
     * 各次 submit 触发的多次派发，合计最多考察 {@code maxGenTasksPerTick} 个候选。它不是「每次派发
     * 一份」，也不是「每 tick 一份」。正常 20 TPS 下窗口与 tick 同档，tick 被拉长时窗口仍按 50 毫秒
     * 滚动，那时真实刻度是墙钟。
     *
     * <p>额度计<b>考察过的候选</b>，在循环顶部扣，与接受还是拒绝无关。一次考察要付快照位置、段集合
     * 与欠账计的读，全被拒时这批成本照样付了；只计成功提交会让这条路径在全拒状态下无限重付。
     *
     * <p>退避未到期的候选不进快照：它们在循环里也只是被跳过，却要先占一次排序的位置。
     */
    private static void dispatch() {
        openBudgetWindow();
        if (budgetLeft <= 0) {
            // 本窗口额度已用完：连候选快照都不建。没有这一段，一次 tick 里的每个 submit 都会付一遍
            // 全表快照与排序，而它们一个作业也交不出去。
            return;
        }
        if (CANDIDATES.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Map.Entry<Long, Candidate>> snapshot = new ArrayList<>(CANDIDATES.entrySet());
        snapshot.removeIf(e -> e.getValue().dueAtMillis > now);
        if (snapshot.isEmpty()) {
            return;
        }
        snapshot.sort(Comparator.comparingLong(e -> distanceOf(e.getValue())));
        Map<ServerLevel, SectionLifecycle.ChunkCursor> cursors = new HashMap<>();
        for (Map.Entry<Long, Candidate> entry : snapshot) {
            if (budgetLeft <= 0) {
                break;
            }
            budgetLeft--;
            long key = entry.getKey();
            Candidate candidate = entry.getValue();
            LevelChunk chunk = candidate.chunk;
            // 同坐标的前一代已交卸载，或身份已注销：条目是死引用，清掉。死身份若不摘，它会被判成整窗
            // 待生成而每轮交出一个 fill，档位却写不进去。
            if (identityOf(chunk) != key || !CounterIdentities.isLive(key)) {
                CANDIDATES.remove(key, candidate);
                StageMetrics.deadCandidateDropped();
                continue;
            }
            if (now < candidate.dueAtMillis) {
                continue;
            }
            // 条件删除：快照之后新提交的同键候选不能被旧候选删掉。
            if (!CANDIDATES.remove(key, candidate)) {
                continue;
            }
            if (!(chunk.getLevel() instanceof ServerLevel level)) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            if (!WorldBounds.inChunkRange(pos.x(), pos.z())) {
                continue;
            }
            SectionLifecycle.ChunkCursor cursor = cursors.computeIfAbsent(level, SectionLifecycle::cursor);
            int ticketLevel = cursor.ticketLevelAt(pos.x(), pos.z());
            int allowed = DependencyTable.allowedStage(ticketLevel);
            // 被阻塞取数等着的那一格不受距离门约束：等待本身证明它必须现在推进。
            boolean waited = isWaited(level, pos.x(), pos.z());
            if (allowed < SectionStage.TERRAIN) {
                retry(key, candidate, waited);
                continue;
            }
            if (!waited && !GenQueue.isNearPlayer(chunk)) {
                // 该维度有玩家、窗口表里却没有它的带，说明刷新还没发生：此刻是「还不知道」，不是
                // 「确实太远」。丢掉等于把活永久丢掉，因为重投与重扫都不在这条路径上。判据取窗口表，
                // 因为准入的竖直面用它。两条出口都自解：刷新之后按正常路径准入，玩家离开之后判据转假，
                // 候选按原语义被丢弃。
                if (!level.players().isEmpty()
                        && EntitySectionWindow.ranges(level.dimension()).length == 0) {
                    requeuePersistent(key, candidate);
                    continue;
                }
                // 确实太远：一次性移除。
                continue;
            }
            int[] sections = sectionsFor(level, chunk, candidate.preloadSections);
            boolean wrDone = ChunkCounters.isWindowReadDone(chunk);
            // 第七行：本候选范围内每段都已点亮，且窗口面读过盘。范围为空不算完成：那是「没有可做的
            // 段」，不是「做完了」。FULL 只对「有人等」的 chunk 承诺，所以这一行排在面与窗口之后。
            if (wrDone && sections.length > 0
                    && DependencyTable.pendingBelowIn(chunk, sections, SectionStage.LIGHTED) == 0) {
                ChunkReadiness.completeFull(chunk);
                clearWaited(level, pos.x(), pos.z());
                continue;
            }
            // 生成支：范围内还有低于 CARVERS 的段。装饰支：本 chunk 已有段停在 CARVERS。两支分开判，
            // 因为段全到 CARVERS 之后生成侧无活可做，而装饰恰恰从那一刻开始。
            boolean gen = sections.length > 0
                    && DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS) > 0;
            boolean deco = SectionStage.anyAt(chunk, SectionStage.CARVERS);
            if (!gen && !deco) {
                // 光照支：整窗段都停在 DECORATED 时，缺口在「装饰已完成、光照没触发」这一档。这一档没有
                // 别的出口，LIGHTED 只能由 notifyGenerated 的认领触发一次，那一次没成就永远没人再来。
                // 所以它必须在这里被当成活：重触发，并按固定节奏留在候选表里，不消耗重投次数。
                if (SectionStage.anyAt(chunk, SectionStage.DECORATED)) {
                    GenQueue.notifyGenerated(chunk);
                    requeuePersistent(key, candidate);
                    continue;
                }
                retry(key, candidate, waited);
                continue;
            }
            if (deco && DecorationFiller.trySubmitDecoration(chunk)) {
                continue;
            }
            if (!gen) {
                // 装饰这一支同样不封顶：提交失败只有两条原因，九格门与光照域锁，两者都是瞬时争用，
                // 而退避到顶只要约十三秒。争用一旦超过这个窗口，候选会离表，此后没有周期性重扫把它
                // 放回来，段就永久停在 CARVERS。固定节奏重投把它变成有界等待。
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
                    submit(chunk);
                } else {
                    if (waited && enabled && inRange) {
                        pinWaited(level, pos.x(), pos.z());
                    }
                    retry(key, candidate, waited);
                }
                continue;
            }
            boolean submitted = GenQueue.enqueueTask(chunk, sections);
            if (submitted) {
                continue;
            } else {
                // 候选已在派发之前离表，这里必须重投。TERRAIN 认领是瞬时争用，退避上限约十二秒；
                // 期间的收尾还会再提交一次同一个 chunk，两路都进候选表，同键覆盖，无害。
                retry(key, candidate, waited);
            }
        }
    }

    /**
     * 本候选该处理的段：显式范围、所有覆盖该格的面、该维度的窗口并集的并集，再按可玩段界与在读状态
     * 过滤，升序去重。
     *
     * <p>
     * 准入的欠账判据与 {@code GenTask} 的收集必须读这一份。三支来源在旧写法里各自成判据，而收集侧
     * 只认窗口，于是面带里的段被准入当成欠账、任务又收不到，每 tick 空转一次。
     *
     * <p>
     * 过滤用 {@link WorldBounds#inSection} 而不是可表示段界：{@code AbstractTerrainFiller} 用
     * {@code minSection*16} 建 {@code NoiseSettings}，可表示界两端乘 16 已越过 int。
     *
     * <p>
     * 升序是前提：{@code GenTask.collectSegments} 只按 {@code sy == curMax+1} 判连续，乱序输入会把
     * 每次收集退化成一堆单段，重复输入会把同一段填两次。
     */
    /**
     * 不带显式范围的那一支：只取覆盖该格的面与窗口并集。事件点与退出读数用这条。
     *
     * <p>
     * 与带 {@code explicit} 的重载同一份实现。事件点手上没有候选，取到的范围与预加载期的显式范围
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

    /**
     * 暂时性拒绝的重投分流：被等待的格走不封顶的那一支。
     *
     * <p>封顶那一支的语义是「暂时性原因，试几下就该放弃，等下一次显式提交」。被等待的格没有那一次显式
     * 提交，提交它的人正阻塞在这个 future 上，所以对它只能用固定节奏重投。
     */
    private static void retry(long key, Candidate candidate, boolean waited) {
        if (waited) {
            requeuePersistent(key, candidate);
        } else {
            requeue(key, candidate);
        }
    }

    /**
     * 暂时性拒绝的重投：带退避，次数到顶即放弃，等下一次显式提交。
     *
     * <p>
     * 到顶那一次是静默丢活：候选离开表，除非法提交再没人管它。
     */
    private static void requeue(long key, Candidate candidate) {
        if (candidate.attempts >= RETRY_MAX_ATTEMPTS) {
            return;
        }
        candidate.attempts++;
        long backoff = RETRY_BACKOFF_MILLIS[Math.min(candidate.attempts - 1, RETRY_BACKOFF_MILLIS.length - 1)];
        candidate.dueAtMillis = System.currentTimeMillis() + backoff;
        CANDIDATES.put(key, candidate);
    }

    /**
     * 光照与装饰两支的重投：固定一档退避，不递增次数，因此永不被丢。
     *
     * <p>
     * 与 {@link #requeue} 分开的理由是语义不同。那边的次数上限表达「暂时性原因，重试几下就放弃」，
     * 而停在 {@code DECORATED} 或装饰提交被门与锁挡回都不是可以放弃的状态：放弃等于永久停摆，所以它
     * 只能有节奏，不能有上限。
     */
    private static void requeuePersistent(long key, Candidate candidate) {
        candidate.dueAtMillis = System.currentTimeMillis()
                + RETRY_BACKOFF_MILLIS[RETRY_BACKOFF_MILLIS.length - 1];
        CANDIDATES.put(key, candidate);
    }

    /** 距离用快照，避免逐候选遍历玩家。 */
    private static long distanceOf(Candidate candidate) {
        LevelChunk chunk = candidate.chunk;
        if (chunk.getLevel() instanceof ServerLevel level) {
            return PlayerSnapshot.nearestDistance(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
        }
        return Long.MAX_VALUE;
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
     * <p>
     * 档位与脏标记只能由主线程写：`flags` 的读改写在池线程上会丢写，而 `stage` 的记账要与它成对。
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
     * <p>
     * 不转投主线程。档位所在的 {@link com.inf.farlands.serialize.SectionState} 字段是 volatile，
     * 读改写在段监视器内，派生记账的增减也在实例锁内，池线程直接写因此是安全的；而转投的代价是读者
     * 看到的是队列尾巴而不是写入结果，判据据此反复判定同一段欠账，重复投写、队列越积越长。
     *
     * <p>
     * 脏标记仍走 {@link #dirty} 的转投：它与编码池的清除之间需要串行，转投那一步是那条串行的载体。
     */
    public static void stage(LevelChunk chunk, int[] sections, int targetStage) {
        if (sections.length == 0) {
            return;
        }
        applyStage(chunk, sections, targetStage);
    }

    /** 读回专用的落档：无条件写，绕过单调闸。语义同 {@link #stage}，供盘上档低于内存档时使用。 */
    public static void restore(LevelChunk chunk, int[] sections, int targetStage) {
        if (sections.length == 0) {
            return;
        }
        for (int sy : sections) {
            SectionStage.restoreStage(chunk, sy, targetStage);
        }
        submit(chunk);
    }

    private static void applyStage(LevelChunk chunk, int[] sections, int targetStage) {
        for (int sy : sections) {
            SectionStage.setStage(chunk, sy, targetStage);
        }
        // 扇出：档位变了，下一相可能就绪，重新登记候选。候选被准入或拒绝后移除，不会自增长。
        submit(chunk);
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
