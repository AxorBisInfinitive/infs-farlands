package com.inf.farlands.terrain.pipeline;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.CounterIdentities;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.terrainFiller.TerrainFiller;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.PlayerSnapshot;
import com.inf.farlands.util.world.WorldBounds;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 地形侧的门面：票型、任务池、认领之后的作业提交、光照触发与关服等待。
 *
 * <p>队列与扫描已删：谁该开工由 {@link Scheduler} 的准入判，本类只在准入通过后接管。三个入队入口
 * 现在都退化成 {@link Scheduler#submit} 的别名，唯一的认领点是 {@link #enqueueTask}，它在
 * {@link OwnershipTable} 的 TERRAIN 相上 CAS。
 *
 * <p>光照衔接按 chunk 级去重，去重靠 LIGHTED 相的认领：{@code notifyGenerated} 取到认领才触发一次
 * fillFrom 与 lightChunk，回调里升段、释放在途、再检查 DECORATED 段。每 chunk 每批次一次。
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

    // 规模在配置层已解析：显式值受 range(1,64) 约束，"auto" 由取值器算出，两者都 >= 1。
    // 这里不再解释 0 哨兵，它已由 "auto" 取代。
    private static final ExecutorService POOL = Executors.newFixedThreadPool(FarlandsConfig.genWorkerThreads, r -> {
        Thread t = new Thread(r, "farlands-gen");
        t.setDaemon(true);
        return t;
    });

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
    static void addGenTicket(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addTicketWithRadius(GEN_WORK_TICKET, pos, 0);
    }

    /** 撤 fill ticket，经 SectionIO.runOnMainThread 回主线程，异步延迟保守无害。 */
    static void removeGenTicket(ServerLevel level, ChunkPos pos) {
        SectionIO.runOnMainThread(
                () -> level.getChunkSource().removeTicketWithRadius(GEN_WORK_TICKET, pos, 0), level);
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
        if (SectionIO.isReading(chunk, sectionY)) {
            return;
        }
        Scheduler.submit(chunk);
    }

    /** XZ 触发：chunk 在主线程短路完成。判据在调度器的准入，这里只提交。 */
    public static void enqueueChunk(LevelChunk chunk) {
        Scheduler.submit(chunk);
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
        int[] range = new int[maxSy - minSy + 1];
        for (int i = 0; i < range.length; i++) {
            range[i] = minSy + i;
        }
        Scheduler.submit(chunk, range);
    }

    /**
     * 调度器准入之后交作业：这里才是唯一的 chunk 级认领点，TERRAIN 认领的 CAS 兼作去重。
     *
     * <p>准入的判据在 {@link Scheduler#tick()}，本方法不再过滤：调用方已判过 A、B、C。
     */
    static void enqueueTask(LevelChunk chunk, int[] preloadSections) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long owner = OwnershipTable.tryClaimChunk(level, OwnershipTable.TERRAIN, chunk.getPos());
        if (owner == 0L) {
            return; // 同 chunk 已有在途任务，去重
        }
        ACTIVE.incrementAndGet();
        try {
            GenTask task = new GenTask(chunk, preloadSections, owner);
            POOL.submit(task::execute);
        } catch (RejectedExecutionException e) {
            ACTIVE.decrementAndGet();
            OwnershipTable.releaseChunk(level, OwnershipTable.TERRAIN, chunk.getPos(), owner);
        }
    }

    /** 在途任务计数：认领在作业里，作业可能还在池里没开跑，所以判空要用计数。 */
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    /** 光照在途计数：LIGHTED 认领在回调里释放，判空要用它，所有权表不能枚举。 */
    private static final AtomicInteger ACTIVE_LIGHT = new AtomicInteger();

    /**
     * 该 chunk 是否在驱动面内：任一玩家面内的切比雪夫距离不超过视距加一，或落在任一任务面的方阵内。
     *
     * <p>这是「面」的唯一查询，准入与「要不要预填群系」共用它。形状取切比雪夫加一，与依赖表那一行的
     * 「距最近玩家 ≤ 视距加一」同源；预填因此比旧的圆盘加二少铺几圈，那只是省掉后来本来也不会被驱动的
     * chunk，不影响正确性。
     *
     * <p>判据在主线程取：快照是每 tick 抓的 volatile 读。
     */
    public static boolean isNearPlayer(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        ChunkPos cp = chunk.getPos();
        if (Scheduler.inAnyFace(level, cp)) {
            return true;
        }
        for (PlayerSnapshot.Entry e : PlayerSnapshot.entries()) {
            if (!e.dimension().equals(level.dimension())) {
                continue;
            }
            int d = Math.max(Math.abs(cp.x() - e.chunkX()), Math.abs(cp.z() - e.chunkZ()));
            if (d <= e.viewDistance() + 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收尾：释放 TERRAIN 认领、减在途计数、把该 chunk 交回准入。
     *
     * <p>续任务不再有单独判据：准入看依赖与段状态，扫过的活由扇出与重扫接住。
     */
    static void completeTask(GenTask task) {
        LevelChunk chunk = task.chunk();
        if (chunk.getLevel() instanceof ServerLevel level) {
            OwnershipTable.releaseChunk(level, OwnershipTable.TERRAIN, chunk.getPos(), task.genOwner());
        }
        ACTIVE.decrementAndGet();
        Scheduler.submit(chunk);
    }

    // 光照衔接：chunk 级去重

    /**
     * 该 chunk 是否有不取包锁的段写在途，即生成任务与光照。
     *
     * <p>下发侧要让开的只有这两支：fill 直写段不走 {@code SectionSerializer.packLockFor}，与序列化并发
     * 会撞调色板的检测；装饰的段写取那把锁，所以它不在此列。
     */
    public static boolean isGeneratingOrLighting(LevelChunk chunk) {
        // 所有权表按坐标键记，身份键喂进去恒不命中：生成侧的 TERRAIN 认领因此从来看不到。
        long key = chunk.getPos().pack();
        return OwnershipTable.anyGenClaim(key)
                || OwnershipTable.isLightedClaimed(chunk.getPos().x(), chunk.getPos().z());
    }

    /**
     * 该 chunk 是否有生成、光照或装饰在途，供 fsa 清理与就绪判定用：在途则不清理该 chunk、不补 FULL。
     *
     * <p>装饰那一支按写域问：一个 chunk 只要落在某次装饰的九格写域内就可能正被写，清理要避开它。玩家写
     * 与下发不再由它让开，两处改看 {@link #isGeneratingOrLighting}。
     */
    public static boolean isChunkBusy(LevelChunk chunk) {
        return isGeneratingOrLighting(chunk)
                || OwnershipTable.isDecoratedClaimed(chunk.getPos().pack());
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

    /** 停服时清掉在途标志与计数。 */
    public static void clearWorldState() {
        BIOME_FILLING.clear();
        ACTIVE.set(0);
        ACTIVE_LIGHT.set(0);
    }

    /** 报告某 section 已生成，触发光照，该 chunk 无在途光照时一次。认领兼作去重。 */
    public static void notifyGenerated(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long owner = OwnershipTable.tryClaimChunk(level, OwnershipTable.LIGHTED, chunk.getPos());
        if (owner != 0L) {
            ACTIVE_LIGHT.incrementAndGet();
            triggerLight(chunk, owner);
        }
    }

    /** 该 chunk 是否有光照任务在途，发包前检查，在途则留队列，等播种完成带正确光照。 */
    public static boolean isLightInFlight(LevelChunk chunk) {
        return OwnershipTable.isLightedClaimed(chunk.getPos().x(), chunk.getPos().z());
    }

    /** 一次 fillFrom 与 lightChunk，完成回调里升段、释放认领、再检查。 */
    private static void triggerLight(LevelChunk chunk, long lightOwner) {
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
                                OwnershipTable.releaseChunk(serverLevel, OwnershipTable.LIGHTED, chunk.getPos(),
                                        lightOwner);
                                ACTIVE_LIGHT.decrementAndGet();
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
                                // 光照在途已落，且 DECORATED 段刚升到 LIGHTED：扇出已在升格里发过一次提交。
                                // 光照期间新到 DECORATED 的段交准入再发一批。
                                Scheduler.submit(chunk);
                            }, serverLevel);
                    return;
                }
                InfsFarlands.LOGGER.error("farlands: light failed chunk={}", chunk.getPos(), t);
                OwnershipTable.releaseChunk(serverLevel, OwnershipTable.LIGHTED, chunk.getPos(), lightOwner);
                ACTIVE_LIGHT.decrementAndGet();
                Scheduler.submit(chunk);
            });
        } else {
            // 防御：非本 port 光引擎，释放认领防卡死，理论上不走。
            OwnershipTable.releaseChunk(serverLevel, OwnershipTable.LIGHTED, chunk.getPos(), lightOwner);
            ACTIVE_LIGHT.decrementAndGet();
        }
    }

    // 关服等待

    /** 全局是否仍有生成、光照或装饰在途。认领在作业里，作业可能还在池里没开跑，所以用计数判空。 */
    public static boolean hasInflightWork() {
        return DECORATION_IN_FLIGHT.get() > 0 || ACTIVE.get() > 0 || ACTIVE_LIGHT.get() > 0;
    }

    /** 关服等待：等全局生成与光照在途收敛，有界。超时由 shutdownSyncFlush 的 isChunkBusy 跳过兜底。 */
    public static void awaitIdle(MinecraftServer server, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
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
}
