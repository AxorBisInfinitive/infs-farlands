package com.inf.farlands.terrain.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.CounterIdentities;
import com.inf.farlands.serialize.CounterIdentity;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 调度器。一条候选队列，加上准入、认领、事件与兜底重扫。主线程独占，池上只投事件。
 *
 * <p>与改造前的关系：候选的产出与准入从 {@code GenQueue} 搬到这里；螺旋扫描被每 200 tick 的兜底
 * 重扫取代；作业体本身不变，仍是 {@link GenTask}，它内部的阶段顺序、线程与副作用逐字沿用。
 *
 * <p>准入四个面：票位允许档、驱动面、窗口并集、认领。缺哪一面都只是这一次不派发，而候选本身被
 * 丢掉，等下一次事件、兜底重扫或调用方重提再来。适配数据那一道门不在这里判，理由见 {@link #admit}。
 *
 * <p>认领用 {@link OwnershipTable}：chunk 级独占加地形高度图写权。认领即「有作业在跑」，两者是同一
 * 件事，所以 {@link #isGeneratingOrLighting} 一族查的就是它。
 *
 * <p>事件：池线程只往并发队列里放记录，主线程 drain 时才动派生结构，也就是在途表、计数器与认领。这条
 * 分工是本节所有并发安全的依据。
 */
public final class Scheduler {

    /** 候选的产出种类，用于读数与排查。 */
    public enum Origin {
        /** 按 XZ 触发：chunk 在主线程短路完成。 */
        CHUNK,
        /** 按 Y 触发：窗口滑入某个 section。 */
        SECTION,
        /** 预加载的显式段带。 */
        PRELOAD,
        /** 依赖推进：某段升档后下游就绪。 */
        EVENT,
        /** 兜底重扫发现欠账。 */
        RESCAN
    }

    /** 事件种类。池线程只投这两种，其余推进都在主线程完成。 */
    public enum EventKind {
        /** 某段升档。预留入口，当前无调用点：升档后的下游唤醒由计数器归零与兜底重扫覆盖。 */
        STAGE_ADVANCED,
        /** 某 chunk 的格状态变了，装饰与结构的门要重判。 */
        CELL_CHANGED
    }

    /** 事件记录。带 chunk 引用与身份：身份用于判它是不是同一代，已注销的整条丢掉。 */
    public record Event(EventKind kind, long identity, LevelChunk chunk) {
    }

    /** 兜底重扫的周期，单位 tick。 */
    private static final int RESCAN_INTERVAL = 200;

    /** 兜底重扫的每 tick 入队预算，防一次全量爆发。 */
    private static final int RESCAN_BUDGET = 16;

    /** 候选队列。同距离时按入队序号，再按 chunk 键，使同一份输入的推进次序可复现。 */
    private static final PriorityQueue<GenTask> CANDIDATES =
            new PriorityQueue<>(Comparator.comparingLong(GenTask::priority).thenComparingLong(GenTask::sequence)
                    .thenComparingLong(GenTask::chunkKey));

    /** 池线程投来的事件。 */
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();

    /** 每 chunk 光照在途标志。光照派发仍是自驱的池侧循环，这里只记在途。 */
    private static final ConcurrentHashMap<Long, AtomicBoolean> LIGHT_IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * 每 level 一个取数游标，在每个 tick 开头换一次。
     *
     * <p>不能每次准入都现建：{@code SectionLifecycle.cursor} 是一次反射读，而准入在一轮爆发里会被调
     * 上千次。也不能长期缓存：游标握着 {@code visibleChunkMap} 的快照引用，而那引用每 tick 被克隆
     * 换掉，握着旧引用会让新 chunk 查不到票位，准入一律失败。折中是每 tick 换一次，与克隆同频。
     */
    private static final ConcurrentHashMap<ServerLevel, SectionLifecycle.ChunkCursor> CURSORS =
            new ConcurrentHashMap<>();

    /** biome 阶段在途的 chunk，按实例记。与光照队列、生成任务都不重叠。 */
    private static final java.util.Set<LevelChunk> BIOME_FILLING = ConcurrentHashMap.newKeySet();

    /** 读数：本轮的准入拒绝，按面分。 */
    private static final long[] REJECT = new long[4];

    private static final int REJECT_TICKET = 0;
    private static final int REJECT_FACE = 1;
    private static final int REJECT_WINDOW = 2;
    private static final int REJECT_CLAIM = 3;

    /** 读数：本轮发出的候选数，与撞上适配数据门被挡下的次数。 */
    private static long emitted;
    private static long blockedJobs;

    /** 兜底重扫的帧计数与轮转起点。 */
    private static int rescanFrame;
    private static int rescanStart;

    /** 派发重入守卫。offer 会调派发，而作业收尾的续任务又会 offer，没有它就会递归。 */
    private static boolean dispatching;

    private static long sequence;

    private Scheduler() {
    }

    // ---- 事件入口，任意线程 ----

    /**
     * 投一条事件。池线程也调，所以只做入队，不碰任何派生结构。
     *
     * <p>身份为 0 的 chunk，即客户端、ProtoChunk 与已注销的那些，直接丢：它不是本调度器的对象。
     */
    public static void post(EventKind kind, long identity, LevelChunk chunk) {
        if (identity != 0L && chunk != null) {
            EVENTS.add(new Event(kind, identity, chunk));
        }
    }

    /** 某个 chunk 的格状态变了，装饰与结构要重判。主线程与池线程都会调。 */
    public static void postCellChanged(LevelChunk chunk) {
        post(EventKind.CELL_CHANGED, identityOf(chunk), chunk);
    }

    // ---- 候选入队，主线程 ----

    /** 某 chunk 入候选。已在途或认领不到时不入队。 */
    public static void enqueue(LevelChunk chunk, int sectionY, Origin origin) {
        if (SectionIO.isReading(chunk.getPos().pack(), sectionY)) {
            return;
        }
        if (!hasWorkInWindow(chunk)) {
            return;
        }
        offer(new GenTask(chunk), origin);
    }

    /** 整 chunk 入候选，只在该 chunk 位于驱动面内时。 */
    public static void enqueueChunk(LevelChunk chunk, Origin origin) {
        if (!isNearPlayer(chunk)) {
            return;
        }
        if (!hasWorkInWindow(chunk)) {
            return;
        }
        offer(new GenTask(chunk), origin);
    }

    /** 显式段带入候选，绕过驱动面过滤；范围内全已过 CARVERS 时不入队。 */
    public static void preload(LevelChunk chunk, int minSy, int maxSy, Origin origin) {
        boolean anyPending = false;
        for (int sy = minSy; sy <= maxSy; sy++) {
            if (!WorldBounds.inSection(sy)) {
                continue;
            }
            if (!SectionStage.isOrAfter(chunk, sy, SectionStage.CARVERS)) {
                anyPending = true;
                break;
            }
        }
        if (!anyPending) {
            return;
        }
        int[] range = new int[maxSy - minSy + 1];
        for (int i = 0; i < range.length; i++) {
            range[i] = minSy + i;
        }
        offer(new GenTask(chunk, range), origin);
    }

    /** 玩家位置变化时把全部在途候选按当前距离重排，修复入队时快照旧。主线程。 */
    public static void rebuild() {
        synchronized (CANDIDATES) {
            if (CANDIDATES.isEmpty()) {
                return;
            }
            List<GenTask> all = new ArrayList<>(CANDIDATES);
            CANDIDATES.clear();
            for (GenTask task : all) {
                task.refreshPriority();
                CANDIDATES.add(task);
            }
        }
    }

    private static void offer(GenTask task, Origin origin) {
        if (!admit(task)) {
            return;
        }
        synchronized (CANDIDATES) {
            CANDIDATES.add(task);
        }
        emitted++;
        // 入队即唤醒，与改造前同规。主线程上不跑 tick 的阶段也要靠这一句推进：预加载跑在
        // prepareLevels 里，保存会占住主线程，两者都不经过 FarlandsTick。只靠每 tick 的 drain，
        // 那些阶段会整段停摆，候选在队列里等到会话结束。
        drainCandidates();
    }

    // ---- 准入 ----

    /**
     * 候选能不能进队列：票位允许档、驱动面、窗口并集、认领，四个面缺一不可。
     *
     * <p>认领在这里落下，因此「已入队」蕴含「已独占」：`isGeneratingOrLighting` 一族不必再另记一张
     * 在途表。放弃时按面计数，供读数定位是哪一面在挡。
     */
    private static boolean admit(GenTask task) {
        LevelChunk chunk = task.chunk();
        long identity = identityOf(chunk);
        if (identity == 0L || chunk.getLevel() == null) {
            return false;
        }
        // 认领先判。它最便宜，而拒绝率最高：同一个 chunk 在作业在途期间会被反复提出，预加载循环每轮
        // 对驱动面整片重提一次。放在票位与窗口之前，省掉那批「已在途」候选的整轮判据与段扫描。
        if (OwnershipTable.chunkClaimed(identity)) {
            REJECT[REJECT_CLAIM]++;
            return false;
        }
        if (!chunk.getLevel().isClientSide() && chunk.getLevel() instanceof ServerLevel level) {
            SectionLifecycle.ChunkCursor cursor = CURSORS.get(level);
            if (cursor == null) {
                cursor = SectionLifecycle.cursor(level);
            }
            int cx = chunk.getPos().x();
            int cz = chunk.getPos().z();
            int ticketLevel = cursor.ticketLevelAt(cx, cz);
            if (DependencyTable.allowedStage(ticketLevel) < task.targetStage()) {
                REJECT[REJECT_TICKET]++;
                return false;
            }
            if (!isNearPlayer(chunk) && !task.isPreload()) {
                REJECT[REJECT_FACE]++;
                return false;
            }
        }
        if (!hasWorkInWindowFor(task)) {
            REJECT[REJECT_WINDOW]++;
            return false;
        }
        // 适配数据就位这一条**不在这里判**：任务体自己会判，而它被挡下那一支还要顺手拉 ±8 依赖。
        // 提前到入队会让这种任务永远进不了池，拉依赖因此没人做，门也就永远开不了。
        long owner = OwnershipTable.newOwner();
        if (!OwnershipTable.claimChunk(identity, owner)) {
            REJECT[REJECT_CLAIM]++;
            return false;
        }
        if (task.targetStage() <= SectionStage.TERRAIN && !OwnershipTable.claimHeightmap(identity, owner)) {
            OwnershipTable.releaseChunk(identity, owner);
            REJECT[REJECT_CLAIM]++;
            return false;
        }
        task.setOwner(owner);
        addGenTicket(chunk);
        return true;
    }

    // ---- 作业收尾 ----

    /**
     * 作业收尾。主线程或池线程都会调：池线程上的收尾只投一笔回主线程，释放认领与票。
     *
     * <p>被门挡下的那一支**不重排**，与改造前同一取舍：那条门只由 ±8 的壳决定，重试改变不了它，
     * 而重试消耗的恰是解开自己要用的池与主线程，会变成不推进任何状态的自旋。恢复交给既有的两条路，
     * 即预加载循环每轮对驱动面整片重提，以及每 200 tick 的兜底重扫，另加结构相就绪后的回投。
     * 被挡的次数单独计一个读数，它是这条门代价的量化。
     *
     * <p>做完的那一支若窗口并集内仍有未 TERRAIN 的段，续一次。
     */
    public static void finishJob(GenTask task, boolean blocked) {
        LevelChunk chunk = task.chunk();
        ServerLevel level = chunk.getLevel() instanceof ServerLevel sl ? sl : null;
        if (level == null) {
            release(task);
            return;
        }
        SectionIO.runOnMainThread(() -> {
            release(task);
            if (blocked) {
                blockedJobs++;
                return;
            }
            if (hasUnprocessed(chunk)) {
                offer(new GenTask(chunk), Origin.EVENT);
            }
        }, level);
    }

    /** 释放认领与票。只能在主线程调。 */
    private static void release(GenTask task) {
        LevelChunk chunk = task.chunk();
        long identity = identityOf(chunk);
        long owner = task.owner();
        OwnershipTable.releaseHeightmap(identity, owner);
        OwnershipTable.releaseChunk(identity, owner);
        removeGenTicket(chunk);
    }

    // ---- 每 tick ----

    /**
     * 主线程每 tick 入口：drain 事件、按预算把候选交给池、按周期兜底重扫。
     */
    public static void tick(MinecraftServer server, int tickCount) {
        // 游标与 visibleChunkMap 的克隆同频更换，见 CURSORS 的注释
        for (ServerLevel level : server.getAllLevels()) {
            CURSORS.put(level, SectionLifecycle.cursor(level));
        }
        drainEvents();
        drainCandidates();
        if (++rescanFrame >= RESCAN_INTERVAL) {
            rescanFrame = 0;
            rescan(server);
        }
        if (FarlandsConfig.logScheduler) {
            InfsFarlands.LOGGER.info(
                    "FLSCHED emitted={} blocked={} depth={} rejectTicket={} rejectFace={} rejectWindow={} rejectClaim={}",
                    emitted, blockedJobs, depth(), REJECT[REJECT_TICKET], REJECT[REJECT_FACE],
                    REJECT[REJECT_WINDOW], REJECT[REJECT_CLAIM]);
        }
    }

    /** 事件 drain：只推候选，不改已经落下的档位。主线程。 */
    private static void drainEvents() {
        Event event;
        while ((event = EVENTS.poll()) != null) {
            if (!CounterIdentities.isLive(event.identity())) {
                continue;
            }
            enqueueChunk(event.chunk(), Origin.EVENT);
        }
    }

    /** 按预算把候选送进池。主线程。重入直接返回，因为 offer 会调它，而它自己提交的作业收尾时又会 offer。 */
    private static void drainCandidates() {
        if (dispatching) {
            return;
        }
        dispatching = true;
        try {
            int budget = FarlandsConfig.maxGenTasksPerTick;
            while (budget-- > 0) {
                GenTask task;
                synchronized (CANDIDATES) {
                    task = CANDIDATES.poll();
                }
                if (task == null) {
                    return;
                }
                GenQueue.submitGen(task);
            }
        } finally {
            dispatching = false;
        }
    }

    /**
     * 兜底重扫：每 200 tick 从驱动面里螺旋式找欠账的 chunk 入候选，每 tick 上限 {@link #RESCAN_BUDGET}。
     *
     * <p>它取代了改造前每 tick 的螺旋扫描：判据与 {@code scanChunk} 同源，逐项照抄，只把周期从每
     * tick 放到 200 tick，并复用同一个预算名。
     */
    private static void rescan(MinecraftServer server) {
        int budget = RESCAN_BUDGET;
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            rescanStart++;
            ServerPlayer player = players.get(Math.floorMod(rescanStart, players.size()));
            int view = DependencyTable.viewDistance(level);
            int pcx = player.chunkPosition().x();
            int pcz = player.chunkPosition().z();
            for (int d = 0; d <= view + 1 && budget > 0; d++) {
                for (int dx = -d; dx <= d && budget > 0; dx++) {
                    for (int dz = -d; dz <= d && budget > 0; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) {
                            continue;
                        }
                        LevelChunk chunk = SectionLifecycle.latestChunk(level, pcx + dx, pcz + dz);
                        if (chunk == null) {
                            continue;
                        }
                        if (hasPendingWork(chunk)) {
                            offer(new GenTask(chunk), Origin.RESCAN);
                            budget--;
                        }
                    }
                }
            }
        }
    }

    /**
     * 该 chunk 是否还有生成侧的欠账。判据与改造前的 {@code scanChunk} 同源：窗口并集内未 TERRAIN、
     * surface 与 carvers 待处理、还有做过没点亮的段，或装饰待办。
     */
    private static boolean hasPendingWork(LevelChunk chunk) {
        if (hasUnprocessed(chunk) || SectionStage.hasBelowLighted(chunk)) {
            return true;
        }
        if (com.inf.farlands.terrain.surfaceFiller.SurfaceFiller.hasSurfacePending(chunk)
                || com.inf.farlands.terrain.carverFiller.CarverFiller.hasCarversPending(chunk)) {
            return true;
        }
        if (com.inf.farlands.terrain.decorationFiller.DecorationFiller.hasDecorationPending(chunk)) {
            com.inf.farlands.terrain.decorationFiller.DecorationFiller.register(chunk, null);
            return true;
        }
        return false;
    }

    // ---- 窗口与驱动面判据 ----

    /** 该 chunk 在窗口并集内是否仍有未 TERRAIN 的段。 */
    private static boolean hasUnprocessed(LevelChunk chunk) {
        boolean[] found = { false };
        EntitySectionWindow.forEachSectionInAnyWindow(sy -> {
            if (!WorldBounds.inSection(sy)) {
                return;
            }
            if (!SectionStage.isOrAfter(chunk, sy, SectionStage.TERRAIN)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** 该 chunk 在窗口并集内是否仍有低于 CARVERS 的段，即生成侧还有活。 */
    private static boolean hasWorkInWindow(LevelChunk chunk) {
        boolean[] found = { false };
        EntitySectionWindow.forEachSectionInAnyWindow(sy -> {
            if (!WorldBounds.inSection(sy)) {
                return;
            }
            if (!SectionStage.isOrAfter(chunk, sy, SectionStage.CARVERS)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** 带显式段带的作业按段带判，窗口模式按窗口并集判。 */
    private static boolean hasWorkInWindowFor(GenTask task) {
        int[] range = task.preloadSections();
        if (range == null) {
            return hasWorkInWindow(task.chunk());
        }
        for (int sy : range) {
            if (WorldBounds.inSection(sy) && !SectionStage.isOrAfter(task.chunk(), sy, SectionStage.CARVERS)) {
                return true;
            }
        }
        return false;
    }

    /** 该 chunk 是否在任一玩家的视距加外圈 1 内，即驱动面内。与改造前同一判据。 */
    public static boolean isNearPlayer(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel sl)) {
            return false;
        }
        var cp = chunk.getPos();
        for (ServerPlayer p : sl.players()) {
            var view = p.getChunkTrackingView();
            int viewDistance = view instanceof net.minecraft.server.level.ChunkTrackingView.Positioned pos
                    ? pos.viewDistance()
                    : 8;
            if (net.minecraft.server.level.ChunkTrackingView.isWithinDistance(
                    p.chunkPosition().x(), p.chunkPosition().z(), viewDistance, cp.x(), cp.z(), true)) {
                return true;
            }
        }
        return false;
    }

    // ---- 在途判据，供 fsa 与下发让路 ----

    /** 该 chunk 是否有不取包锁的段写在途，即生成任务或光照。 */
    public static boolean isGeneratingOrLighting(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (OwnershipTable.chunkClaimed(identity)) {
            return true;
        }
        AtomicBoolean light = LIGHT_IN_FLIGHT.get(chunk.getPos().pack());
        return light != null && light.get();
    }

    /** 该 chunk 是否有生成、光照或装饰在途。装饰那一支按写域问。 */
    public static boolean isChunkBusy(LevelChunk chunk) {
        if (isGeneratingOrLighting(chunk)) {
            return true;
        }
        return com.inf.farlands.terrain.decorationFiller.DecorationClaim.isClaimed(
                ((ServerLevel) chunk.getLevel()).dimension(), chunk.getPos().pack());
    }

    // ---- biome 阶段在途 ----

    public static void beginBiomeFill(LevelChunk chunk) {
        BIOME_FILLING.add(chunk);
    }

    public static void endBiomeFill(LevelChunk chunk) {
        BIOME_FILLING.remove(chunk);
    }

    public static boolean isBiomeFilling(LevelChunk chunk) {
        return BIOME_FILLING.contains(chunk);
    }

    // ---- 光照衔接 ----

    /** 报告某 section 已生成，触发光照；该 chunk 无在途光照时一次。 */
    public static void notifyGenerated(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        if (LIGHT_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            GenQueue.triggerLight(chunk);
        }
    }

    /** 光照任务收尾时释放在途标志，并检查是否还有需要下一批的段。 */
    public static void lightFinished(LevelChunk chunk) {
        LIGHT_IN_FLIGHT.remove(chunk.getPos().pack());
        if (SectionStage.hasAwaitingLight(chunk)) {
            notifyGenerated(chunk);
        }
    }

    /** 该 chunk 是否有光照任务在途。 */
    public static boolean isLightInFlight(LevelChunk chunk) {
        AtomicBoolean inFlight = LIGHT_IN_FLIGHT.get(chunk.getPos().pack());
        return inFlight != null && inFlight.get();
    }

    // ---- 票 ----

    private static void addGenTicket(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel sl) {
            sl.getChunkSource().addTicketWithRadius(GenQueue.GEN_WORK_TICKET, chunk.getPos(), 0);
        }
    }

    private static void removeGenTicket(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel sl) {
            SectionIO.runOnMainThread(
                    () -> sl.getChunkSource().removeTicketWithRadius(GenQueue.GEN_WORK_TICKET, chunk.getPos(), 0), sl);
        }
    }

    // ---- 生命周期 ----

    /** 全局是否仍有生成、光照或装饰在途，或候选非空。 */
    public static boolean hasInflightWork() {
        if (GenQueue.decorationInFlight() > 0) {
            return true;
        }
        if (!EVENTS.isEmpty()) {
            return true;
        }
        synchronized (CANDIDATES) {
            return !CANDIDATES.isEmpty();
        }
    }

    /** 停服时清空候选、事件与在途标志。 */
    public static void clearWorldState() {
        synchronized (CANDIDATES) {
            CANDIDATES.clear();
        }
        EVENTS.clear();
        LIGHT_IN_FLIGHT.clear();
        BIOME_FILLING.clear();
        CURSORS.clear();
        OwnershipTable.clearAll();
        sequence = 0;
        rescanFrame = 0;
    }

    private static int depth() {
        synchronized (CANDIDATES) {
            return CANDIDATES.size();
        }
    }

    /** 作业入队序号，用于并列时的次序。 */
    static long nextSequence() {
        return ++sequence;
    }

    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof CounterIdentity identity ? identity.counterId() : 0L;
    }
}
