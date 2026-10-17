package com.inf.farlands.terrain.pipeline;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.CounterIdentities;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.decorationFiller.DecorationClaim;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.terrainFiller.TerrainFiller;
import com.inf.farlands.terrain.surfaceFiller.SurfaceFiller;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.world.WorldBounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 地形管线生成任务队列。per-chunk 任务，全局并发队列，多个 worker 异步消费。
 * drainGen 仿光照 drainLight，调度单例用 CAS，任务 submit 到池。
 *
 * 每次唤醒 submit 不超过 maxGenTasksPerTick 个，宽松防风暴。唤醒源是 tick 与入队的 enqueue。
 *
 * 光照衔接按 chunk 级去重：GenTask 只推进到 TERRAIN，随后 notifyGenerated 触发一次 fillFrom 与
 * lightChunk。每 chunk 每批次一次，CAS 在途标志去重，不逐 section 重复播种。光照完成回调在主线程
 * 那一笔里把 SectionStage.isAwaitingLight 认的段升 LIGHTED，播种覆盖全 chunk，然后释放在途，
 * hasAwaitingLight 再检查驱动下一批。
 */
public final class GenQueue {

    /**
     * fill 任务期间保持 chunk 加载的 ticket，半径 0，只保加载不卸载。
     * 与光照那张票同型但不同实例，两票在同一 chunk 上互不覆盖。
     *
     * 26.1.2 的 TicketType 是 record，没有 mod 侧静态工厂，类型须注册进
     * BuiltInRegistries.TICKET_TYPE，且必须在 freeze 之前。注册时机由 BuiltInRegistriesMixin
     * 在 bootStrap() 头部调 ensureTicketTypeRegistered() 把本类 clinit 提前，类初始化恰好一次，
     * 因此不需要幂等守卫。
     */
    public static final TicketType GEN_WORK_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            InfsFarlands.id("gen_work"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

    /**
     * 结构依赖的 pin 票：给中心引用相要读的 ±8 起点表那些邻居铺的加载票。
     *
     * <p>不能复用 {@link #GEN_WORK_TICKET}：那张票的生命周期属于该 chunk 自己的生成任务，
     * completeTask 一收尾就撤；依赖票必须活到中心的引用相读完为止。
     *
     * <p>票在 {@code TicketStorage} 里是集合语义、不计数：同型同级再加一次只刷新计时，撤一次就
     * 没了。所以引用计数由 StructureDriver 自己维护，本类只负责真的加减。
     */
    public static final TicketType STRUCTURE_PIN_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            InfsFarlands.id("structure_pin"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

    /** 触发本类 clinit，即上面两张票型的注册。仅供 BuiltInRegistriesMixin 在冻结前调用。 */
    public static TicketType ensureTicketTypeRegistered() {
        return GEN_WORK_TICKET;
    }

    /** 铺一张结构依赖票。主线程调用，票操作非线程安全，调用点负责，见 StructureDriver.pullDependencies。 */
    public static void pinStructureDependency(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addTicketWithRadius(STRUCTURE_PIN_TICKET, pos, 0);
    }

    /**
     * 撤一张结构依赖票。票操作非线程安全，而本方法的调用点里有卸载线程，所以自己 marshal 回主线程，
     * 形状与 {@link #removeGenTicket} 相同。半径必须与铺下去的那次逐字相同。
     */
    public static void unpinStructureDependency(ServerLevel level, ChunkPos pos) {
        SectionIO.runOnMainThread(
                () -> level.getChunkSource().removeTicketWithRadius(STRUCTURE_PIN_TICKET, pos, 0), level);
    }

    /** 生成任务队列，按距最近玩家距离排序，近先生成。PriorityQueue 非线程安全，用 QUEUE 自身同步。 */
    private static final PriorityQueue<GenTask> QUEUE = new PriorityQueue<>(Comparator.comparingInt(GenTask::priority));

    // 规模在配置层已解析：显式值受 range(1,64) 约束，"auto" 由取值器算出，两者都 >= 1。
    // 这里不再解释 0 哨兵，它已由 "auto" 取代。
    private static final ExecutorService POOL = Executors.newFixedThreadPool(FarlandsConfig.genWorkerThreads, r -> {
        Thread t = new Thread(r, "farlands-gen");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean consumerActive = new AtomicBoolean();

    /** 每 chunk 光照在途标志，键是记账身份，值为 CAS，由收尾回调移除。 */
    private static final ConcurrentHashMap<Long, AtomicBoolean> LIGHT_IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * 每 chunk 生成在途标志，键是记账身份，值为 CAS，同 chunk 至多一个生成任务，防并发写高度图。
     *
     * <p>被门挡下那一支置假而不删，条目留在表里，等 {@link #sweepDeadEntries()} 在身份注销后收走。
     */
    private static final ConcurrentHashMap<Long, AtomicBoolean> CHUNK_IN_FLIGHT = new ConcurrentHashMap<>();

    /** 本 chunk 的记账身份。客户端 chunk 与 ProtoChunk 是 0。 */
    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof com.inf.farlands.serialize.CounterIdentity identity ? identity.counterId() : 0L;
    }

    /**
     * 活着的身份，死的返回 0，入队侧据此拒绝。
     *
     * <p>两张在途表的键是身份：同坐标的前后两代落在两个键上，旧代的收尾碰不到新一代的条目。
     * 入队判活使死键成为终态，清扫因此上界一轮。
     */
    private static long liveIdentity(LevelChunk chunk) {
        long identity = identityOf(chunk);
        return CounterIdentities.isLive(identity) ? identity : 0L;
    }

    /**
     * biome 阶段在途的 chunk，按 chunk 实例记，同坐标重载不互相牵连。
     *
     * <p>短路完成后的群系填充跑在 Util.backgroundExecutor 上，既不是生成任务也不是光照任务，
     * 上面两张标志都不覆盖它。它同样会在两次遍历之间换掉 section 的 biomes 容器，所以发送侧
     * 必须另查本集合，否则按前一次算出的定长缓冲会在写时越界。
     */
    private static final Set<LevelChunk> BIOME_FILLING = ConcurrentHashMap.newKeySet();

    /**
     * 装饰任务在途计数，关服等待用。
     *
     * <p>加在提交处，减在装饰的收尾里，不是在任务体结束时减：收尾是异步投回主线程的一笔，
     * 若在任务体末尾就减，awaitIdle 会在收尾还没跑的时候放行，那一笔的升段与撤认领就丢了。
     *
     * <p>与 ENCODE_TASKS_IN_FLIGHT 同规：停服时不清零，未收尾的任务还会来减，清零会被减成负数。
     */
    private static final AtomicInteger DECORATION_IN_FLIGHT = new AtomicInteger();

    /**
     * 把装饰任务提交到 genPool。返回假表示池已关，即停服中；调用方撤认领、留表稍后再试。
     *
     * <p>与地形任务共用同一个池：装饰是纯算力，它必须跑在 farlands-gen 上。计数在这里加，
     * 由 DecorationFiller 的收尾经 {@link #finishDecoration()} 减。
     */
    public static boolean submitDecoration(Runnable job) {
        DECORATION_IN_FLIGHT.incrementAndGet();
        try {
            POOL.submit(job);
            return true;
        } catch (RejectedExecutionException e) {
            DECORATION_IN_FLIGHT.decrementAndGet();
            return false;
        }
    }

    /** 装饰收尾完成时减计数。由 DecorationFiller 的收尾调用，主线程。 */
    public static void finishDecoration() {
        DECORATION_IN_FLIGHT.decrementAndGet();
    }

    private GenQueue() {
    }

    /** 取该 level 的 TerrainFiller。填充器实例随 level 走，由 LevelSystems 持有。 */
    static TerrainFiller filler(ServerLevel level) {
        return ((LevelSystems) level).terrainFiller();
    }

    // 主线程：触发入队

    /** fill 在途保加载。ticket 操作非线程安全，调用点保证主线程。 */
    private static void addGenTicket(LevelChunk chunk) {
        ServerLevel sl = (ServerLevel) chunk.getLevel();
        sl.getChunkSource().addTicketWithRadius(GEN_WORK_TICKET, chunk.getPos(), 0);
    }

    /** genPool 线程移除 fill ticket，经 SectionIO.runOnMainThread 回主线程，异步延迟保守无害。 */
    private static void removeGenTicket(LevelChunk chunk) {
        ServerLevel sl = (ServerLevel) chunk.getLevel();
        SectionIO.runOnMainThread(
                () -> sl.getChunkSource().removeTicketWithRadius(GEN_WORK_TICKET, chunk.getPos(), 0),
                sl);
    }

    /**
     * Y 触发：主线程上的单 section 请求。幂等，已点亮不入队，入队粒度是 chunk 级任务。
     *
     * <p>门取 LIGHTED 而不是 TERRAIN：读回后停在 TERRAIN 到 CARVERS 之间的段也需要一次入队，
     * 由 GenTask 的光照补触发收口。取 TERRAIN 会把窗口滑入的单段读回直接挡掉，那条路的段在
     * 恢复后已经 isOrAfter 为 TERRAIN。
     */
    public static void enqueue(LevelChunk chunk, int sectionY) {
        // fsa 读回在途：该 section 正在从磁盘恢复，完成回调会再调 enqueue。
        if (SectionIO.isReading(chunk.getPos().pack(), sectionY)) {
            return;
        }
        if (!hasWorkInWindow(chunk)) {
            return;
        }
        // 键取身份，死身份不入队：客户端 chunk 与 ProtoChunk 是 0，已注销的旧对象也是 0。
        long key = liveIdentity(chunk);
        if (key == 0L) {
            return;
        }
        if (CHUNK_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            addGenTicket(chunk);
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk));
            }
            wakeConsumer();
        }
        // 已在途时不重复入队。在途任务 execute 会扫窗口并集，含此 section，execute 后新入队的
        // 由 completeTask 再检查兜底。
    }

    /**
     * XZ 触发：chunk 在主线程短路完成，处于任一玩家视距或外圈内才入队。
     * 距离判断直接按玩家坐标算，不依赖 tracking view，它每 tick 才更新，新加载 chunk 若此刻
     * 不在其中会永远错过入队。
     */
    public static void enqueueChunk(LevelChunk chunk) {
        if (!isNearPlayer(chunk)) {
            return;
        }
        if (!hasWorkInWindow(chunk)) {
            return;
        }
        // 键取身份，死身份不入队：客户端 chunk 与 ProtoChunk 是 0，已注销的旧对象也是 0。
        long key = liveIdentity(chunk);
        if (key == 0L) {
            return;
        }
        if (CHUNK_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            addGenTicket(chunk);
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk));
            }
            wakeConsumer();
        }
    }

    /**
     * 该 chunk 在窗口并集内是否还有生成侧的活：存在低于 CARVERS 的段。
     *
     * <p>与 {@link #preload} 的门同源，区别只在范围：preload 的范围是显式段带，这里是窗口并集。带内全
     * ≥ CARVERS 时任务什么也做不了，逐条推导见 preload 的注释。
     *
     * <p>为什么不能只看本次要入队的那一段：窗口模式的任务扫整个窗口并集，另有一段仍在 TERRAIN 时它照样
     * 有事可做，所以判据必须落在范围上，而不是落在触发它的那一段上。
     */
    private static boolean hasWorkInWindow(LevelChunk chunk) {
        boolean[] found = { false };
        EntitySectionWindow.forEachSectionInAnyWindow(chunk.getLevel().dimension(), sy -> {
            if (!WorldBounds.inSection(sy)) {
                return;
            }
            if (!SectionStage.isOrAfter(chunk, sy, SectionStage.CARVERS)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 预加载：指定 section 范围入队，绕过 tracking view 过滤。幂等，范围内全已生成则不入队。
     *
     * <p>门取 CARVERS，也就是生成侧欠账的终点。带内全 ≥ CARVERS 时这个任务什么也做不了，可以逐条推：
     * collectSegments 只收未过 TERRAIN 的段，空；surface 与 carvers 的待处理判据都不认 CARVERS 段，空；
     * 末尾的点亮触发只认 DECORATED 一档，CARVERS 不在其中，也空。所以放它进去只是一次空跑。而出生区
     * 预加载的循环每轮都对同一圈块重调本方法，空跑会被放大成每秒几百次。
     *
     * <p>低于 CARVERS 的档全部放行：缺席的段要建，BIOMES 要 fill，TERRAIN 要 surface，SURFACE 要 carve，
     * 这四件事都在 GenTask 里连着做完，也都需要这次入队。
     */
    public static void preload(LevelChunk chunk, int minSy, int maxSy) {
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
        long key = liveIdentity(chunk);
        if (key == 0L) {
            return;
        }
        if (CHUNK_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            addGenTicket(chunk);
            int[] range = new int[maxSy - minSy + 1];
            for (int i = 0; i < range.length; i++) {
                range[i] = minSy + i;
            }
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk, range));
            }
            wakeConsumer();
        }
    }

    /**
     * 该 chunk 是否在任一玩家的视距或外圈 1 内，即驱动面内。
     *
     * <p>入队与「要不要预填群系」共用它：视距之外加载的 chunk 不会被驱动，给它们铺群系的那笔气候采样
     * 白做。判据在主线程取：players 是普通 List，后台线程不该遍历它。
     */
    public static boolean isNearPlayer(LevelChunk chunk) {
        Level level = chunk.getLevel();
        if (!(level instanceof ServerLevel sl)) {
            return false;
        }
        ChunkPos cp = chunk.getPos();
        for (ServerPlayer p : sl.players()) {
            ChunkTrackingView view = p.getChunkTrackingView();
            int viewDistance = view instanceof ChunkTrackingView.Positioned pos ? pos.viewDistance() : 8;
            if (ChunkTrackingView.isWithinDistance(
                    p.chunkPosition().x(), p.chunkPosition().z(), viewDistance, cp.x(), cp.z(), true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * execute 完成回调：被门挡下的那一支不续任务，其余仍按窗口并集判。
     *
     * <p>续任务的用途只有一个：覆盖 execute 期间新入队的段，所以判据是「窗口并集内仍有未 TERRAIN 的
     * 段」。但被门挡下时这个判据恒为真，而任务此刻什么也做不了，因为没有 Beardifier 就不许 fill，
     * 于是续任务成为一条不推进任何状态、却占满池与主线程的自旋。被挡的 chunk 改由两条路再触发：扫描
     * 的有预算重试，以及 farlandsStructureReady 设好 Beardifier 之后的回投。
     *
     * <p>标志不在续任务时清：否则会留下标志已清、任务尚未入队的空窗，那期间 isChunkBusy 返回假，
     * 读 section 的一方会与生成写并发。被挡那一支用 set(false) 而不是 remove：remove 会让并发的
     * computeIfAbsent 造出第二个 AtomicBoolean，两个 CAS 都能成功，同 chunk 于是出现两个任务并发写
     * 同一批高度图。工作做完那一支仍用 remove，任务链结束后条目不永存，防随探索单调增长。
     */
    static void completeTask(LevelChunk chunk, boolean blocked) {
        // 键是本任务自己的身份：即便它已注销，字段仍在，所以收尾仍能寻址自己那一格。
        long key = identityOf(chunk);
        if (!blocked && hasUnprocessed(chunk)) {
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk));
            }
            wakeConsumer();
            return;
        }
        if (blocked) {
            AtomicBoolean inFlight = CHUNK_IN_FLIGHT.get(key);
            if (inFlight != null) {
                inFlight.set(false);
            }
        } else {
            CHUNK_IN_FLIGHT.remove(key);
        }
        removeGenTicket(chunk);
        // 生成在途落：该 chunk 可能就此就绪。本方法跑在 genPool 上，补 FULL 要回主线程，与撤票同形。
        if (chunk.getLevel() instanceof ServerLevel serverLevel) {
            SectionIO.runOnMainThread(() -> ChunkReadiness.completeFull(chunk), serverLevel);
        }
    }

    /** 该 chunk 在窗口并集内是否仍有未 TERRAIN 的 section。 */
    private static boolean hasUnprocessed(LevelChunk chunk) {
        boolean[] found = { false };
        EntitySectionWindow.forEachSectionInAnyWindow(chunk.getLevel().dimension(), sy -> {
            if (!WorldBounds.inSection(sy)) {
                return;
            }
            if (!SectionStage.isOrAfter(chunk, sy, SectionStage.TERRAIN)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** onServerTick 每 tick 唤醒，submit 一批。 */
    public static void tick() {
        wakeConsumer();
    }

    /** 每 tick 扫描入队预算，渐进分批，不 burst。 */
    private static final int SCAN_BUDGET = 32;

    /**
     * 动态扫描：每 tick 从每个玩家当前位置螺旋向外扫描视距及外圈内的 chunk，未生成的按当前
     * 距离近先入队，生成顺序天然跟随玩家位置。budget 是 per-player 配额，多人下后遍历的玩家
     * 不会饥饿。
     */
    public static void scanAndEnqueue(MinecraftServer server) {
        int totalPlayers = 0;
        for (ServerLevel level : server.getAllLevels()) {
            totalPlayers += level.players().size();
        }
        int perPlayer = Math.max(1, SCAN_BUDGET / Math.max(1, totalPlayers));
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                int budget = perPlayer;
                ChunkPos pc = p.chunkPosition();
                ChunkTrackingView viewObj = p.getChunkTrackingView();
                int view = viewObj instanceof ChunkTrackingView.Positioned pos ? pos.viewDistance() : 8;
                for (int d = 0; d <= view + 1 && budget > 0; d++) {
                    if (d == 0) {
                        if (scanChunk(level, pc.x(), pc.z())) {
                            budget--;
                        }
                        continue;
                    }
                    for (int x = -d; x <= d && budget > 0; x++) {
                        if (scanChunk(level, pc.x() + x, pc.z() - d) && --budget <= 0) {
                            break;
                        }
                        if (scanChunk(level, pc.x() + x, pc.z() + d) && --budget <= 0) {
                            break;
                        }
                    }
                    for (int z = -d + 1; z <= d - 1 && budget > 0; z++) {
                        if (scanChunk(level, pc.x() - d, pc.z() + z) && --budget <= 0) {
                            break;
                        }
                        if (scanChunk(level, pc.x() + d, pc.z() + z) && --budget <= 0) {
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * 扫描单个 chunk：已加载 LevelChunk 且窗口并集内有未 TERRAIN 的 section、surface 与 carvers
     * 待处理，或仍有做过但没点亮的段，则 enqueueChunk，幂等。
     */
    private static boolean scanChunk(ServerLevel level, int cx, int cz) {
        LevelChunk lc = SectionLifecycle.latestChunk(level, cx, cz);
        if (lc == null) {
            return false;
        }
        AtomicBoolean inflight = CHUNK_IN_FLIGHT.get(identityOf(lc));
        if (inflight != null && inflight.get()) {
            return false;
        }
        if (!hasUnprocessed(lc) && !SurfaceFiller.hasSurfacePending(lc)
                && !CarverFiller.hasCarversPending(lc) && !SectionStage.hasBelowLighted(lc)) {
            return false;
        }
        // 已过雕刻、还没装饰的 chunk 登记进装饰表。装饰有自己的门与驱动，这里只负责发现；
        // 判据取 CARVERS，与 DecorationFiller.hasDecorationPending 同源。
        if (DecorationFiller.hasDecorationPending(lc)) {
            DecorationFiller.register(lc, null);
        }
        enqueueChunk(lc);
        return true;
    }

    /** 玩家位置变化时全部在途任务按当前距离重排，修复入队快照旧。 */
    public static void rebuildQueue() {
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) {
                return;
            }
            List<GenTask> all = new ArrayList<>(QUEUE);
            QUEUE.clear();
            for (GenTask t : all) {
                t.refreshPriority();
                QUEUE.offer(t);
            }
        }
    }

    // 光照衔接：chunk 级去重

    /**
     * 该 chunk 是否有不取包锁的段写在途，即生成任务与光照。
     *
     * <p>下发侧要让开的只有这两支：fill 直写段不走 {@code SectionSerializer.packLockFor}，与序列化并发
     * 会撞调色板的检测；装饰的段写取那把锁，所以它不在此列。
     */
    public static boolean isGeneratingOrLighting(LevelChunk chunk) {
        long key = identityOf(chunk);
        AtomicBoolean gen = CHUNK_IN_FLIGHT.get(key);
        if (gen != null && gen.get()) {
            return true;
        }
        AtomicBoolean light = LIGHT_IN_FLIGHT.get(key);
        return light != null && light.get();
    }

    /**
     * 该 chunk 是否有生成、光照或装饰在途，供 fsa 清理与就绪判定用：在途则不清理该 chunk、不补 FULL。
     *
     * <p>装饰那一支按写域问：一个 chunk 只要落在某次装饰的九格写域内就可能正被写，清理要避开它。玩家写
     * 与下发不再由它让开，两处改看 {@link #isGeneratingOrLighting}。
     */
    public static boolean isChunkBusy(LevelChunk chunk) {
        return isGeneratingOrLighting(chunk)
                || DecorationClaim.isClaimed(chunk.getLevel().dimension(), chunk.getPos().pack());
    }

    /**
     * biome 阶段开始。
     *
     * <p>必须在提交群系填充任务之前、由主线程调用：发送是主线程上连续的两条语句，标志只能在
     * 它们之外落下，才能在填充的第一笔写之前生效。
     */
    public static void beginBiomeFill(LevelChunk chunk) {
        BIOME_FILLING.add(chunk);
    }

    /** biome 阶段结束。由填充任务的 finally 调用，填充抛异常时同样要清，否则该 chunk 永久为忙。 */
    public static void endBiomeFill(LevelChunk chunk) {
        BIOME_FILLING.remove(chunk);
    }

    /** 该 chunk 的 biome 阶段是否在途。发送侧与补发标记据此判定，见 WindowSendState.sendableSections。 */
    public static boolean isBiomeFilling(LevelChunk chunk) {
        return BIOME_FILLING.contains(chunk);
    }

    /** 停服时清掉队列与三张在途标志。队列里持有的都是旧 LevelChunk。 */
    public static void clearWorldState() {
        synchronized (QUEUE) {
            QUEUE.clear();
        }
        CHUNK_IN_FLIGHT.clear();
        LIGHT_IN_FLIGHT.clear();
        BIOME_FILLING.clear();
        consumerActive.set(false);
    }

    /**
     * A：周期清扫，删掉「身份已注销且值为假」的条目。
     *
     * <p>谓词只用条目自身：键是身份，值是标志。值为假的条目不参与任何判据面的读数，删它不改变任何
     * 读者的结果；值为真的条目留给它自己的收尾，所以清扫不会撤掉让路判据。
     *
     * <p>线程：认领只在三处 CAS，全在主线程，本方法也在主线程，所以与认领不交错，只与池线程上的
     * 两条收尾出口并发。删除走 {@code remove(key, value)}，带值比对，不会误删同键换上的新实例；
     * 收尾那一侧的 blocked 支先判空，做完支的 remove 幂等，条目已被删时两条出口都空转。
     *
     * <p>收敛上界一轮：死键上的假条目本轮删净；死键上的真条目留给其收尾，收尾若置假则下一轮删净。
     */
    public static void sweepDeadEntries() {
        for (Map.Entry<Long, AtomicBoolean> e : CHUNK_IN_FLIGHT.entrySet()) {
            if (e.getValue().get() || CounterIdentities.isLive(e.getKey())) {
                continue;
            }
            CHUNK_IN_FLIGHT.remove(e.getKey(), e.getValue());
        }
    }

    /** 报告某 section 已生成，触发光照，该 chunk 无在途光照时一次。 */
    public static void notifyGenerated(LevelChunk chunk) {
        long key = liveIdentity(chunk);
        if (key == 0L) {
            return;
        }
        if (LIGHT_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            triggerLight(chunk);
        }
    }

    /** 该 chunk 是否有光照任务在途，发包前检查，在途则留队列，等播种完成带正确光照。 */
    public static boolean isLightInFlight(LevelChunk chunk) {
        long key = identityOf(chunk);
        AtomicBoolean inFlight = LIGHT_IN_FLIGHT.get(key);
        return inFlight != null && inFlight.get();
    }

    /** 一次 fillFrom 与 lightChunk，完成回调里升段、释放、再检查。 */
    private static void triggerLight(LevelChunk chunk) {
        long key = identityOf(chunk);
        ServerLevel serverLevel = (ServerLevel) chunk.getLevel();
        if (serverLevel.getChunkSource().getLightEngine() instanceof FarLandsLightEngine lightEngine) {
            chunk.initializeLightSources();
            // fillFrom 完成，触发播种时登记等待本 chunk 的邻居重播，修正边界方向位。
            lightEngine.onChunkSkySourcesReady(chunk.getPos());
            // 这里量的是延迟，即提交到回调执行的墙钟，含排队等待，不是光照的工作线程时间。
            long lightStart = System.nanoTime();
            lightEngine.lightChunk(chunk, false).whenComplete((c, t) -> {
                StageMetrics.stageWork(StageMetrics.STAGE_LIGHTED, System.nanoTime() - lightStart);
                if (t == null) {
                    // 升格搬在主线程那一笔里，与落盘入队、广播、标记、drive 同批，理由两条：池线程只投
                    // 事件、不碰派生结构；升格又必须在 persistChunkDirty 之前，因为编码侧只放行不低于
                    // LIGHTED 的段，先入队后升格会让那一批在编码时被拒。
                    //
                    // 释放在途标志与再检查也排进这一笔，且释放在升格之后：升格已可见，而再检查若在升格
                    // 之前跑，会读到尚未升格的 DECORATED 计数，多触发一次完整光照。
                    SectionIO.runOnMainThread(
                            () -> {
                                SectionStage.promoteAllGenToLighted(chunk);
                                LIGHT_IN_FLIGHT.remove(key);
                                // 光照完成，该 chunk 脏 section 入队 PERSIST，此时 fill 与光照都已完成，数据完整。
                                SectionLifecycle.persistChunkDirty(chunk);
                                // 播种完成，主动广播该 chunk 全部光照，含空 section 的 15。增量包链依赖 chunk
                                // 达 ENTITY_TICKING，而空壳先发加光照后补的管线里播种时常常未达，主动广播绕开
                                // 这个依赖，播种完成即发。
                                ChunkDataSender.broadcastChunkLight(serverLevel, chunk);
                                // 段在这一刻才升 LIGHTED，promote 在上一步已跑完，而下发与落盘同门，
                                // 所以必须在这里补一次内容变化标记：否则门一加，整包永远不发，客户端
                                // 只能看到空壳。
                                ChunkDataSender.markChunkChanged(chunk);
                                // 光照在途已落，且 DECORATED 段刚升到 LIGHTED：该 chunk 可能就此就绪，补一次 FULL。
                                // 主线程上直调，主线程若正阻塞在某个等 FULL 的位置上，这一笔会在 managedBlock 的
                                // 队列里执行，把等待解开。
                                ChunkReadiness.completeFull(chunk);
                                if (SectionStage.hasAwaitingLight(chunk)) {
                                    // 光照期间新到 DECORATED 的段未被这次播种覆盖，下一批。
                                    notifyGenerated(chunk);
                                }
                            }, serverLevel);
                    return;
                }
                InfsFarlands.LOGGER.error("farlands: light failed chunk={}", chunk.getPos(), t);
                LIGHT_IN_FLIGHT.remove(key);
                if (SectionStage.hasAwaitingLight(chunk)) {
                    notifyGenerated(chunk);
                }
            });
        } else {
            // 防御：非本 port 光引擎，释放标志防卡死，理论上不走。
            LIGHT_IN_FLIGHT.remove(key);
        }
    }

    // 关服等待

    /** 全局是否仍有生成、光照或装饰在途，任一标志为真或生成队列非空即为真。 */
    public static boolean hasInflightWork() {
        if (DECORATION_IN_FLIGHT.get() > 0) {
            return true;
        }
        for (AtomicBoolean b : CHUNK_IN_FLIGHT.values()) {
            if (b.get()) {
                return true;
            }
        }
        for (AtomicBoolean b : LIGHT_IN_FLIGHT.values()) {
            if (b.get()) {
                return true;
            }
        }
        synchronized (QUEUE) {
            return !QUEUE.isEmpty();
        }
    }

    /**
     * 关服等待：等全局生成与光照在途收敛，有界。每轮唤醒消费，drainGen 消费不完不续唤醒，
     * 超时由 shutdownSyncFlush 的 isChunkBusy 跳过兜底。
     */
    public static void awaitIdle(MinecraftServer server, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            wakeConsumer();
            // 关键：抽主执行器。群系填充收尾与读回完成都投回主线程跑，不抽它们，那些在途标志永远落不
            // 下来，本等待就只会等满 timeout。
            SectionIO.drainMainThreadTasks(server);
            if (!hasInflightWork()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // 调度

    private static void wakeConsumer() {
        if (consumerActive.compareAndSet(false, true)) {
            try {
                POOL.submit(GenQueue::drainGen);
            } catch (RejectedExecutionException e) {
                consumerActive.set(false);
            }
        }
    }

    private static void drainGen() {
        try {
            int budget = FarlandsConfig.maxGenTasksPerTick;
            for (int i = 0; i < budget; i++) {
                GenTask task;
                synchronized (QUEUE) {
                    task = QUEUE.poll();
                }
                if (task == null) {
                    break;
                }
                POOL.submit(task::execute);
            }
        } catch (RejectedExecutionException e) {
            // 池关闭时剩余任务丢弃，daemon 不主动关，理论不触发
        } finally {
            consumerActive.set(false);
        }
    }
}
