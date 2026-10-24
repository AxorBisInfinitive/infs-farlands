package com.inf.farlands.light;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.util.pos.IntSectionPos;
import com.inf.farlands.util.window.WindowedChunk;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.Util;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * FarLands 光照引擎，替代 vanilla 的 {@code LevelLightEngine} 与
 * {@code ThreadedLevelLightEngine}。
 *
 * <p>
 * 继承 {@code ThreadedLevelLightEngine} 以与 {@code ChunkMap} 和
 * {@code ServerChunkCache} 字段类型兼容。{@code super()} 创建的 vanilla sky/block
 * 引擎不使用 所有 public 方法都被覆写，委托给 {@link FarLandsSkyLightEngine} 与
 * {@link FarLandsBlockLightEngine}。
 *
 * <p>
 * 服务端并行：服务端构造即 ChunkMap 版启用 per-chunk 任务队列 +
 * 后台传播线程池 + per-chunk 锁半径 2 + light ticket 保证任务期间 chunk 不卸载。
 * 主线程/生成线程只入队；客户端构造保持同步直调，渲染线程每帧 runLightUpdates。
 * 双路径通过 {@code serverSide} 区分，客户端不受影响。
 *
 * <p>
 * 26.1.2：父类构造器的线程组件由 {@code ProcessorMailbox/ProcessorHandle}
 * 换成 {@code ConsecutiveExecutor/ChunkTaskDispatcher}；ticket 由
 * {@code addRegionTicket/removeRegionTicket} 换成
 * {@code addTicketWithRadius/removeTicketWithRadius}；{@code lightOnInSection}
 * 改名 {@code lightOnInColumn}。
 */
public class FarLandsLightEngine extends ThreadedLevelLightEngine {

    final FarLandsSkyLightEngine skyEngine;
    final FarLandsBlockLightEngine blockEngine;
    final LightChunkGetter chunkSource;

    /** 邻居 chunkKey -> 播种时等它的 chunk 集合，邻居 fillFrom 后触发重播修正边界方向位。 */
    private static final ConcurrentHashMap<Long, Set<Long>> SKY_WAITERS = new ConcurrentHashMap<>();

    public static void registerSkyWaiter(long neighborKey, long waiterKey) {
        SKY_WAITERS.computeIfAbsent(neighborKey, k -> ConcurrentHashMap.newKeySet()).add(waiterKey);
    }

    /** 本 chunk SkyLightSources fillFrom 完成 -> 触发等待它的 chunk 重播，修正边界方向位。 */
    public void onChunkSkySourcesReady(ChunkPos pos) {
        Set<Long> waiters = SKY_WAITERS.remove(pos.pack());
        if (waiters == null)
            return;
        for (long wk : waiters) {
            // R1-B：pos 面向 waiter 的 16 边界列全露天，getLowestSourceY 全 MIN_VALUE ->
            // waiter 往 pos 的方向位与 fill 中相同，同为 MIN，属 L2 剪枝 -> 重播无效果，跳过。
            if (boundaryAllOpen(pos, wk)) {
                continue;
            }
            enqueueLightSeeding(ChunkPos.unpack(wk));
        }
    }

    /** pos fillFrom 后面向 waiter 的边界列是否全露天，供 R1-B 跳过判定。 */
    private boolean boundaryAllOpen(ChunkPos pos, long waiterKey) {
        int cx = pos.x(), cz = pos.z();
        int wcx = ChunkPos.getX(waiterKey), wcz = ChunkPos.getZ(waiterKey);
        LightChunk lc = chunkSource.getChunkForLighting(cx, cz);
        if (lc == null) {
            return false; // 保守：无法判断 -> 重播
        }
        ChunkSkyLightSources src = lc.getSkyLightSources();
        if (src == null) {
            return false; // 保守处理：fill 中不触发本方法，fillFrom 完成才到，防御
        }
        if (wcz < cz) { // waiter 在北 -> pos 的 z==15 列
            for (int i = 0; i < 16; i++) {
                if (src.getLowestSourceY(i, 15) != Integer.MIN_VALUE) {
                    return false;
                }
            }
        } else if (wcz > cz) { // waiter 在南 -> pos 的 z==0 列
            for (int i = 0; i < 16; i++) {
                if (src.getLowestSourceY(i, 0) != Integer.MIN_VALUE) {
                    return false;
                }
            }
        } else if (wcx < cx) { // waiter 在西 -> pos 的 x==15 列
            for (int i = 0; i < 16; i++) {
                if (src.getLowestSourceY(15, i) != Integer.MIN_VALUE) {
                    return false;
                }
            }
        } else { // waiter 在东 -> pos 的 x==0 列
            for (int i = 0; i < 16; i++) {
                if (src.getLowestSourceY(0, i) != Integer.MIN_VALUE) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 播种任务，lightChunk 与邻居 fillFrom 触发的边界修正重播共用。 */
    private void enqueueLightSeeding(ChunkPos pos) {
        FarLandsLightQueue.ChunkWork tasks = queue.queueChunkLighting(pos, () -> {
            // R1-A：重播只播 sky 边界列增量，因邻居 fillFrom 后方向位修正 block 光来自
            // 方块发光、不受邻居 fillFrom 影响，跳过 block 播种/传播；边界列传播自然扩散内部。
            FarLandsSkyLightEngine sky = getSkyForTask();
            try {
                if (skyEngine != null) {
                    sky.propagateLightSourcesBoundary(pos);
                    sky.runPropagation();
                }
            } finally {
                releaseSky(sky);
            }
        });
        // 必须 onEnqueued，它负责 addWorkRef + CHUNK_WORK_TICKET：executeTask 完成时
        // 无条件 removeWorkRef，若重播不 add 会误扣同 chunk 在途 light 任务的 ticket
        // 引用 -> 保加载被抽 -> 卸载竞态 -> 光照损坏，这是"预加载区外光照全坏"的根因。
        // 顺带唤醒后台调度，否则队列空闲时重播任务永不执行，边界修正丢失。
        onEnqueued(tasks);
    }

    // 服务端并行，客户端为 null

    private final boolean serverSide;
    private final FarLandsLightQueue queue;
    private final ExecutorService lightPool;
    private final ArrayDeque<FarLandsSkyLightEngine> skyPool = new ArrayDeque<>();
    private final ArrayDeque<FarLandsBlockLightEngine> blockPool = new ArrayDeque<>();
    private final FarLandsDataLayerStorage sharedSkyStorage;
    private final FarLandsDataLayerStorage sharedBlockStorage;
    private final ConcurrentHashMap<Long, Integer> sharedSkyTopSections;

    /** Server-side constructor called via {@code ChunkMap}. */
    public FarLandsLightEngine(
            LightChunkGetter chunkSource,
            ChunkMap chunkMap,
            boolean skyLight,
            ConsecutiveExecutor consecutiveExecutor,
            ChunkTaskDispatcher taskDispatcher) {
        super(chunkSource, chunkMap, skyLight, consecutiveExecutor, taskDispatcher);
        this.chunkSource = chunkSource;
        this.serverSide = true;
        this.sharedBlockStorage = new FarLandsDataLayerStorage();
        this.sharedSkyStorage = new FarLandsDataLayerStorage();
        this.sharedSkyTopSections = new ConcurrentHashMap<>();
        this.blockEngine = new FarLandsBlockLightEngine(chunkSource, sharedBlockStorage);
        this.skyEngine = skyLight ? new FarLandsSkyLightEngine(chunkSource, sharedSkyStorage, sharedSkyTopSections)
                : null;
        this.queue = new FarLandsLightQueue();
        // 已由 FarlandsConfig 解析：显式值受 range(1,64) 约束，"auto" 由取值器算出，恒 >= 1
        int parallelism = FarlandsConfig.parallelLightThreads;
        this.lightPool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "farlands-light");
            t.setDaemon(true);
            return t;
        });
    }

    /** Client-side constructor called via {@code ClientChunkCache}. */
    public FarLandsLightEngine(LightChunkGetter chunkSource, boolean hasSkyLight) {
        super(chunkSource, null, hasSkyLight,
                new ConsecutiveExecutor(Util.backgroundExecutor(), "farlands-light"),
                null);
        this.chunkSource = chunkSource;
        this.serverSide = false;
        this.blockEngine = new FarLandsBlockLightEngine(chunkSource);
        this.skyEngine = hasSkyLight ? new FarLandsSkyLightEngine(chunkSource) : null;
        this.queue = null;
        this.lightPool = null;
        this.sharedSkyStorage = null;
        this.sharedBlockStorage = null;
        this.sharedSkyTopSections = null;
    }

    @Override
    public void close() {
        if (lightPool != null) {
            lightPool.shutdownNow(); // 强制中断，防池线程卡任务不退出，线程池泄漏
        }
    }

    @Override
    protected void updateChunkStatus(ChunkPos pos) {
        // 服务端/客户端一致：同步清理该 chunk 光照层，CHM 线程安全；与传播并发为
        // 弱一致瞬态，重载/下次任务修正。卸载不排队 chunk 即将卸载，ticket 无意义。
        blockEngine.removeChunk(pos);
        if (skyEngine != null)
            skyEngine.removeChunk(pos);
    }

    @Override
    public CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean isLighted) {
        if (serverSide) {
            ChunkPos pos = chunk.getPos();
            if (isLighted) {
                chunk.setLightCorrect(true);
                return CompletableFuture.completedFuture(chunk);
            }
            CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
            FarLandsLightQueue.ChunkWork tasks = queue.queueChunkLighting(pos, () -> {
                // 播种，后台传播线程执行
                FarLandsSkyLightEngine sky = getSkyForTask();
                FarLandsBlockLightEngine blk = getBlockForTask();
                try {
                    blk.propagateLightSources(pos);
                    if (skyEngine != null) {
                        sky.propagateLightSources(pos);
                    }
                    // 播种只 enqueue 光源，必须 propagate 才扩散；原同步模型靠每 tick
                    // runLightUpdates 处理队列，任务模型里播种任务结束后无人处理。
                    blk.runPropagation();
                    if (skyEngine != null) {
                        sky.runPropagation();
                    }
                } finally {
                    releaseSky(sky);
                    releaseBlock(blk);
                }
                chunk.setLightCorrect(true);
            });
            tasks.onComplete.whenComplete((v, t) -> {
                if (t != null) {
                    result.completeExceptionally(t);
                } else if (!result.isDone()) {
                    result.complete(chunk);
                }
            });
            onEnqueued(tasks);
            return result;
        }
        if (chunk != null && !isLighted) {
            propagateLightSources(chunk.getPos());
        }
        chunk.setLightCorrect(true);
        return CompletableFuture.completedFuture(chunk);
    }

    // LightEventListener

    @Override
    public void checkBlock(BlockPos pos) {
        if (serverSide) {
            onEnqueued(queue.queueBlockChange(pos));
            return;
        }
        blockEngine.checkBlock(pos);
        if (skyEngine != null)
            skyEngine.checkBlock(pos);
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean isEmpty) {
        if (serverSide) {
            onEnqueued(queue.queueSectionChange(pos, isEmpty));
            return;
        }
        blockEngine.updateSectionStatus(pos, isEmpty);
        if (skyEngine != null)
            skyEngine.updateSectionStatus(pos, isEmpty);
    }

    @Override
    public boolean hasLightWork() {
        if (serverSide) {
            return queue.hasWork();
        }
        return (skyEngine != null && skyEngine.hasLightWork())
                || blockEngine.hasLightWork();
    }

    @Override
    public int runLightUpdates() {
        if (serverSide) {
            scheduleTasks();
            return 0;
        }
        int i = 0;
        i += blockEngine.runLightUpdates();
        if (skyEngine != null)
            i += skyEngine.runLightUpdates();
        return i;
    }

    @Override
    public void setLightEnabled(ChunkPos pos, boolean enabled) {
        blockEngine.setLightEnabled(pos, enabled);
        if (skyEngine != null)
            skyEngine.setLightEnabled(pos, enabled);
    }

    @Override
    public void propagateLightSources(ChunkPos pos) {
        blockEngine.propagateLightSources(pos);
        if (skyEngine != null)
            skyEngine.propagateLightSources(pos);
    }

    // LevelLightEngine 覆写

    @Override
    public int getRawBrightness(BlockPos pos, int skyDarken) {
        int sky = skyEngine == null ? 0 : skyEngine.getLightValue(pos) - skyDarken;
        int block = blockEngine.getLightValue(pos);
        return Math.max(block, sky);
    }

    @Override
    public LayerLightEventListener getLayerListener(LightLayer layer) {
        if (layer == LightLayer.BLOCK)
            return blockEngine;
        return skyEngine != null ? skyEngine : LayerLightEventListener.DummyLightLayerEventListener.INSTANCE;
    }

    @Override
    public void queueSectionData(LightLayer layer, SectionPos pos, DataLayer data) {
        long chunkKey = ChunkPos.pack(pos.x(), pos.z());
        if (layer == LightLayer.BLOCK) {
            // null = 清空该 section 层（客户端卸载/丢弃/增量包清空信号） 必须真正删层，
            // 不能走 updateSectionStatus(true)（其 no-op 是服务端 decrease 传播语义，
            // 层保留到 chunk 卸载；客户端清空路径依赖删层恢复 getLightValue 搜索/15）。
            if (data != null)
                blockEngine.setDataLayer(pos.asLong(), data, chunkKey);
            else
                blockEngine.removeDataLayer(pos.asLong(), chunkKey);
        } else if (skyEngine != null) {
            if (data != null)
                skyEngine.setDataLayer(pos.asLong(), data, chunkKey);
            else
                skyEngine.removeDataLayer(pos.asLong(), chunkKey);
        }
    }

    /** 移除某 section 的光照层，供 fsa 清理：section 滑出写盘后清引擎层，随 section 存走。 */
    public void removeSectionData(LightLayer layer, SectionPos pos) {
        long key = pos.asLong();
        long chunkKey = ChunkPos.pack(pos.x(), pos.z());
        if (layer == LightLayer.BLOCK) {
            blockEngine.removeDataLayer(key, chunkKey);
        } else if (skyEngine != null) {
            skyEngine.removeDataLayer(key, chunkKey);
        }
    }

    /**
     * 装饰侧取光照域锁：半径 2，覆盖它的 3×3 写域。主线程调用，失败即放弃本次、留表重试。
     *
     * <p>
     * 锁已搬进 {@link com.inf.farlands.terrain.pipeline.OwnershipTable} 的二十五格相：光照侧在那里
     * 查装饰认领表，装饰侧直接取锁，两个方向互为排除。客户端构造下没有该表，装饰是纯服务端，返回假。
     */
    public long tryLockDomain(int chunkX, int chunkZ) {
        if (!this.serverSide) {
            return 0L;
        }
        return com.inf.farlands.terrain.pipeline.OwnershipTable.tryLockDomain(
                (ServerLevel) this.chunkSource.getLevel(), chunkX, chunkZ, ChunkPos.pack(chunkX, chunkZ));
    }

    /** 装饰侧释放光照域锁。与 {@link #tryLockDomain} 配对，收尾调用，必须带回取域时的编号。 */
    public void unlockDomain(int chunkX, int chunkZ, long owner) {
        if (this.serverSide) {
            com.inf.farlands.terrain.pipeline.OwnershipTable.unlockCells(chunkX, chunkZ, owner);
        }
    }

    @Override
    public boolean lightOnInColumn(long sectionZeroNode) {
        long key = sectionZeroNode;
        if (blockEngine.getDataLayer(key) != null)
            return true;
        if (skyEngine != null && skyEngine.getDataLayer(key) != null)
            return true;
        // 兜底：chunk 已加载 = 可编译，修复幽灵块
        IntSectionPos sp = IntSectionPos.getSectionPos(key);
        return chunkSource.getChunkForLighting(sp.x, sp.z) != null;
    }

    @Override
    public void retainData(ChunkPos pos, boolean retain) {
        // 无操作 单层存储无保留概念
    }

    // 新增公共 API

    /** Direct access for persistence (§5 packets, ChunkSerializer). */
    public DataLayer getSkyDataLayer(SectionPos pos) {
        return skyEngine == null ? null : skyEngine.getDataLayer(pos.asLong());
    }

    /** Direct access for persistence (§5 packets, ChunkSerializer). */
    public DataLayer getBlockDataLayer(SectionPos pos) {
        return blockEngine.getDataLayer(pos.asLong());
    }

    // initializeLight：异步

    @Override
    public CompletableFuture<ChunkAccess> initializeLight(ChunkAccess chunk, boolean lightEnabled) {
        return CompletableFuture.supplyAsync(() -> {
            if (chunk instanceof WindowedChunk wc) {
                for (var e : wc.windowedAllSections().entrySet()) {
                    LevelChunkSection s = e.getValue();
                    if (s == null || s.hasOnlyAir())
                        continue;
                    SectionPos sp = SectionPos.of(chunk.getPos(), e.getKey());
                    blockEngine.updateSectionStatus(sp, false);
                    if (skyEngine != null)
                        skyEngine.updateSectionStatus(sp, false);
                }
            }
            return chunk;
        });
    }

    public FarLandsLightPacketData buildLightPacket(ChunkPos pos) {
        var sky = new Int2ObjectOpenHashMap<byte[]>();
        var block = new Int2ObjectOpenHashMap<byte[]>();
        var lc = chunkSource.getChunkForLighting(pos.x(), pos.z());
        // getChunkForLighting 可能返回 ChunkSerializer.read 的 ImposterProtoChunk 它的
        // allSections 只含窗口 section，构造时从窗口视图转移，极端 Y section 数据在 wrapped
        // LevelChunk，loadWindowSections 写入 ipc.getWrapped() 必须解包取真实数据，否则
        // 打包 0 层，重进极端 Y 光照黑。
        if (lc instanceof ImposterProtoChunk ipc) {
            lc = ipc.getWrapped();
        }
        if (!(lc instanceof WindowedChunk wc))
            return new FarLandsLightPacketData(sky, block);
        for (var e : wc.windowedAllSections().entrySet()) {
            int sy = e.getKey();
            LevelChunkSection sec = e.getValue();
            if (sec == null || sec.hasOnlyAir())
                continue;
            SectionPos sp = SectionPos.of(pos, sy);
            if (skyEngine != null) {
                DataLayer sl = skyEngine.getDataLayer(sp.asLong());
                // 层存在就发，含全 0 遮挡层 只发非空层会让客户端无层，
                // sky.getLightValue 无层返回 15 -> 深地下遮挡为 0，全亮。
                if (sl != null)
                    sky.put(sy, sl.copy().getData());
            }
            DataLayer bl = blockEngine.getDataLayer(sp.asLong());
            if (bl != null)
                block.put(sy, bl.copy().getData());
        }
        return new FarLandsLightPacketData(sky, block);
    }

    // ChunkMap 兼容

    private boolean runningLightUpdates;

    @Override
    public void tryScheduleUpdate() {
        if (serverSide) {
            scheduleTasks();
            return;
        }
        if (!runningLightUpdates && hasLightWork()) {
            runningLightUpdates = true;
            try {
                runLightUpdates();
            } finally {
                runningLightUpdates = false;
            }
        }
    }

    @Override
    public CompletableFuture<?> waitForPendingTasks(int x, int z) {
        if (serverSide) {
            return queue.getChunkSyncFuture(x, z);
        }
        return CompletableFuture.completedFuture(null);
    }

    // 调度 / 池 / ticket

    /** 玩家位置变化 -> 光照任务队列按距离重排，近的先处理；原 FIFO 远处先入队先处理。 */
    public void rebuildLightQueue() {
        if (queue != null) {
            queue.rebuildQueue((ServerLevel) chunkSource.getLevel());
        }
    }

    /**
     * 后台调度激活标志 保证同时只有一个 drainLight 调度循环。
     * 入队即唤醒，onEnqueued 触发，任务完成不依赖主线程下一 tick，退出/保存等
     * 主线程忙循环场景下在途任务仍会完成，lightChunk future 落地。
     */
    private final AtomicBoolean consumerActive = new AtomicBoolean();

    /**
     * 取本次 drain 轮的批次上界。计时已删，改为每次唤醒从配置直取；无 tick 阶段的边界由入队即唤醒
     * 与主线程泵的 tryScheduleUpdate 提供。
     */
    private int acquireBudget() {
        return Math.max(1, FarlandsConfig.maxLightTasksPerTick);
    }

    /**
     * 退出 drain 轮：还回激活标志，闭合"退出瞬间真 tick 到达"竞态（那一方的 wakeConsumer
     * 已被本轮的 CAS 吞掉，配额却已发下）。
     *
     * <p>
     * 只有本轮真的提交过任务才自唤。撞满重试上限而一次都没提交的那一轮必须停手：否则「每轮有界、
     * 轮数无界」，自唤把有界重试接成新的无限自旋。那种情况交给下一次入队或主线程泵的唤醒。
     *
     * <p>
     * 注意这里**不能**无条件重排自己：配额未到就重排会让 drain 轮无缝接续，退化成不受限的忙转。
     * 下一个边界由真 tick、边界到达后的主线程泵（tryScheduleUpdate）、或新入队提供——
     * 卡住时 Server thread 正在 managedBlock 里跑主线程任务（ServerChunkCache:630 的
     * tryScheduleUpdate），所以该唤醒源在加载/保存阶段始终存在。
     */
    private void exitDrain(boolean progressed) {
        consumerActive.set(false);
        if (progressed && queue.hasWork()) {
            wakeConsumer();
        }
    }

    /** 唤醒后台调度循环，CAS 单例，已在跑则跳过。 */
    private void wakeConsumer() {
        if (consumerActive.compareAndSet(false, true)) {
            try {
                lightPool.submit(this::drainLight);
            } catch (RejectedExecutionException e) {
                consumerActive.set(false); // 池已关闭，回滚激活标志
            }
        }
    }

    /**
     * 后台调度循环运行在池线程，单例。poll pendingWork -> takeTask -> tryLock ->
     * submit executeTask，执行仍在池线程，并行传播不退化。
     *
     * <p>
     * 真限量：每 tick 最多提交 {@code FarlandsConfig.maxLightTasksPerTick} 个任务
     * （配额由 {@link #acquireBudget()} 发放）。锁冲突的重排不消耗预算（那是重试，不是进展），
     * 否则相邻 chunk 占锁时整轮预算会被重试烧光。
     *
     * <p>
     * 退出时**必须**释放 consumerActive：它是 wakeConsumer CAS 的唯一闸门，而唯一能清它的
     * 地方就是本方法。若预算耗尽时留着 true，之后三条唤醒路径（executeTask finally、
     * onEnqueued、tryScheduleUpdate）的 CAS 全部失败，队列里剩下的键永久搁浅——chunk 的
     * LIGHT future 永不完成 → 到不了 FULL → ChunkMap.saveAllChunks 死等
     * （IntegratedServer.initServer 的 saveEverything 卡住，进度屏冻结、无日志无异常）。
     */
    private void drainLight() {
        boolean progressed = false;
        try {
            int budgetGranted = acquireBudget();
            if (budgetGranted <= 0) {
                return; // 本窗配额已用完，出口统一在 finally
            }
            int budget = budgetGranted;
            int retries = 0;
            int limit = retryLimit(budgetGranted);
            while (budget > 0) {
                long key = queue.nextDirty();
                if (key == Long.MIN_VALUE) {
                    break;
                }
                FarLandsLightQueue.ChunkWork tasks = queue.takeTask(key);
                if (tasks == null) {
                    if (++retries >= limit) {
                        break;
                    }
                    continue; // 已被并发取走，takeTask 原子 remove，空转一次
                }
                int cx = tasks.chunkX();
                int cz = tasks.chunkZ();
                long lockOwner = com.inf.farlands.terrain.pipeline.OwnershipTable.tryLockLightTask(
                        (ServerLevel) this.chunkSource.getLevel(), cx, cz);
                if (lockOwner == 0L) {
                    queue.requeue(tasks); // 相邻任务占用，重排队尾下轮重试
                    if (++retries >= limit) {
                        break; // 本轮试够了：键已回队尾，剩下的键与它一起交给下一个 tick
                    }
                    Thread.yield();
                    continue;
                }
                try {
                    lightPool.submit(() -> executeTask(tasks, cx, cz, lockOwner));
                } catch (Throwable t) {
                    // 池已关闭或提交被拒：锁必须还、任务必须放回。否则这个 ChunkWork 既不在
                    // workMap 也不在 pendingWork，没人执行也没人 requeue，onComplete 永不完成，
                    // LIGHTED 认领永久为真，块锁也永远不释放。与 wakeConsumer 的处理对齐。
                    com.inf.farlands.terrain.pipeline.OwnershipTable.unlockCells(cx, cz, lockOwner);
                    queue.requeue(tasks);
                    InfsFarlands.LOGGER.error("farlands: light submit failed chunk={},{}", cx, cz, t);
                    return;
                }
                budget--;
                progressed = true;
            }
        } finally {
            // consumerActive 是 wakeConsumer CAS 的唯一闸门，任何出口漏掉它都会让队列永久搁浅。
            exitDrain(progressed);
        }
    }

    /**
     * 一轮 drain 允许的重试次数。重试不占推进预算，因为那是重试而不是进展，但次数必须有界：锁冲突时键
     * 回到队尾，若没有上限，这一轮的两个出口都不会动，队列排空与预算耗尽都等不到，循环会一直转下去。
     *
     * <p>
     * 与预算同比例，使「一轮能做多少事」与「一轮能试多少」同阶。下限 8 保证预算为 1 时也试得起。
     */
    private static int retryLimit(int budgetGranted) {
        return Math.max(8, budgetGranted * 2);
    }

    /**
     * 每 tick / 每次主线程泵的调度入口：计时已删，有活就唤醒；`wakeConsumer` 自身 CAS 限流，
     * 不会往池里塞重复的空 drain。
     */
    private void scheduleTasks() {
        if (queue == null) {
            return;
        }
        if (queue.hasWork()) {
            wakeConsumer();
        }
    }

    private void executeTask(FarLandsLightQueue.ChunkWork tasks, int cx, int cz, long lockOwner) {
        try {
            if (tasks.isUnload) {
                blockEngine.removeChunk(new ChunkPos(cx, cz));
                if (skyEngine != null)
                    skyEngine.removeChunk(new ChunkPos(cx, cz));
            } else {
                FarLandsSkyLightEngine sky = getSkyForTask();
                FarLandsBlockLightEngine blk = getBlockForTask();
                try {
                    if (tasks.seedTasks != null) {
                        for (Runnable r : tasks.seedTasks) {
                            r.run();
                        }
                    }
                    if (!tasks.blockChanges.isEmpty() || tasks.sectionChanges != null) {
                        blk.processBlocksChanged(cx, cz, tasks.blockChanges, tasks.sectionChanges);
                        if (skyEngine != null) {
                            sky.processBlocksChanged(cx, cz, tasks.blockChanges, tasks.sectionChanges);
                        }
                    }
                } finally {
                    releaseSky(sky);
                    releaseBlock(blk);
                }
            }
        } catch (Throwable t) {
            InfsFarlands.LOGGER.error("Light task exception chunk={},{}", cx, cz, t);
        } finally {
            com.inf.farlands.terrain.pipeline.OwnershipTable.unlockCells(cx, cz, lockOwner);
            tasks.onComplete.complete(null);
            // 任务完成补唤醒：防 drainLight 单例漏调度 残留任务由在跑任务的
            // 任务完成即唤醒：计时已删，唤醒自身 CAS 限流，不会自旋。
            wakeConsumer();
            // ticket 移除必须主线程，ticket 操作非线程安全
            mainExecutor().execute(() -> {
                if (queue.removeWorkRef(tasks.chunkKey) <= 0) {
                    ServerLevel level = (ServerLevel) chunkSource.getLevel();
                    level.getChunkSource().removeTicketWithRadius(
                            FarLandsLightQueue.CHUNK_WORK_TICKET,
                            new ChunkPos(cx, cz), 0);
                }
            });
        }
    }

    /** 入队后加 light ticket，必须主线程；非主线程调用点如生成线程先回主线程。 */
    private void onEnqueued(FarLandsLightQueue.ChunkWork tasks) {
        // 入队即唤醒后台调度，不依赖主线程下一 tick。
        wakeConsumer();
        if (tasks.isTicketAdded) {
            return;
        }
        tasks.isTicketAdded = true;
        if (mainExecutor().isSameThread()) {
            addTicketFor(tasks);
        } else {
            mainExecutor().execute(() -> addTicketFor(tasks));
        }
    }

    private void addTicketFor(FarLandsLightQueue.ChunkWork tasks) {
        long key = tasks.chunkKey;
        if (queue.addWorkRef(key) == 1) {
            ServerLevel level = (ServerLevel) chunkSource.getLevel();
            level.getChunkSource().addTicketWithRadius(
                    FarLandsLightQueue.CHUNK_WORK_TICKET,
                    new ChunkPos(tasks.chunkX(), tasks.chunkZ()), 0);
        }
    }

    private static final Field F_MAIN_EXECUTOR;
    static {
        try {
            F_MAIN_EXECUTOR = ChunkMap.class.getDeclaredField("mainThreadExecutor");
            F_MAIN_EXECUTOR.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings({ "resource", "unchecked" })
    private BlockableEventLoop<Runnable> mainExecutor() {
        ServerLevel level = (ServerLevel) chunkSource.getLevel();
        try {
            return (BlockableEventLoop<Runnable>) F_MAIN_EXECUTOR.get(level.getChunkSource().chunkMap);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 引擎池：一任务一实例，共享 storage

    private FarLandsSkyLightEngine getSkyForTask() {
        synchronized (skyPool) {
            FarLandsSkyLightEngine e = skyPool.pollFirst();
            if (e != null) {
                return e;
            }
        }
        return new FarLandsSkyLightEngine(chunkSource, sharedSkyStorage, sharedSkyTopSections);
    }

    private void releaseSky(FarLandsSkyLightEngine e) {
        synchronized (skyPool) {
            skyPool.addFirst(e);
        }
    }

    private FarLandsBlockLightEngine getBlockForTask() {
        synchronized (blockPool) {
            FarLandsBlockLightEngine e = blockPool.pollFirst();
            if (e != null) {
                return e;
            }
        }
        return new FarLandsBlockLightEngine(chunkSource, sharedBlockStorage);
    }

    private void releaseBlock(FarLandsBlockLightEngine e) {
        synchronized (blockPool) {
            blockPool.addFirst(e);
        }
    }
}
