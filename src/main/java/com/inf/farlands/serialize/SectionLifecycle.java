package com.inf.farlands.serialize;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.debug.CarveLoopProbe;
import com.inf.farlands.debug.StageSlotProbe;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.pipeline.DependencyTable;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.terrain.surfaceFiller.SurfaceFiller;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * fsa 生命周期层。负责清理判定、编码队列、批调度、内存管理与读回。
 *
 * encode 一律在主线程。待编码队列 pendingEncode 用 ConcurrentLinkedDeque，卸载线程 flushChunk 入队，
 * 编码池 drain 消费，只存 chunk、sectionY 与 mode。编码时现取最新状态，因此等待期间发生的 setBlock
 * 与光照变化都会被编码捕捉。
 *
 * 一轮 drain 在 {@code ENCODE_PASS_BUDGET} 内出队、判写入者、编码，再按维度归批投回主线程记账。
 * CLEANUP 即清理。commit 后删内存，同时删 section、stage、dirty 与光照层。
 * PERSIST 即定期或卸载。commit 后清 dirty，不删内存。
 * encode 或写失败则不 commit，dirty 保留，等下次重试。
 *
 * 四个触发点。cleanup 在窗口变化时把边界外的脏 section 入队 CLEANUP。flushChunk 在卸载时把脏
 * section 入队 PERSIST。flushAllDirty 周期性地把所有脏 section 入队 PERSIST。shutdownSyncFlush 关服
 * 同步兜底。唤醒一律在入队点打，不再有每 tick 的 tick()。
 *
 * 读回：窗口并集或显式段范围发起，置 {@code windowReadDone} 闩，逐段标 READING、读盘、恢复档位与
 * 光照。读回完成交调度器的准入，就绪判据与 FULL 补发都在那里。
 *
 * 26.1.2 的 ChunkMap 没有 getChunks，public 遍历入口只剩 forEachReadyToSendChunk，而它不是全量。
 * 这里改用反射读 ChunkMap.visibleChunkMap，类型是 Long2ObjectLinkedOpenHashMap 装 ChunkHolder，
 * 即全部可见 ChunkHolder。ChunkHolder.getLatestChunk 声明在父类 GenerationChunkHolder 上。
 * stage 由 SectionStage 承载，取值见那一类。terrain 侧调用收口在 TerrainHooks。
 */
public final class SectionLifecycle {

    /** 每 tick 清理入队上限。释放不扣预算，它只限「入队落盘」那一支。 */
    public static final int CLEANUP_BUDGET = 1024;

    /**
     * 本轮清理是否因预算耗尽而没做完。
     *
     * <p>
     * 唯一来源是「已点亮、脏、且待入队」的段超出 {@link #CLEANUP_BUDGET}。调用方据此在窗口静止时
     * 续跑：窗口变化才触发的话，积压只会在下一次窗口变化前一直留着。
     */
    private static boolean cleanupOpen;

    /** 待编码单元。只存引用，编码时现取最新，主线程串行因此无竞态。mode 是 cleanup 语义。 */
    private record EncodeUnit(LevelChunk chunk, int sectionY, boolean cleanup) {
    }

    /** commit 后动作的单位。cleanup 删内存，persist 清 dirty。 */
    private record CleanupUnit(LevelChunk chunk, int sectionY) {
    }

    /** 待编码队列。卸载线程入队，主线程 tick 消费，因此线程安全。 */
    private static final ConcurrentLinkedDeque<EncodeUnit> pendingEncode = new ConcurrentLinkedDeque<>();

    /**
     * 停服时清掉按世界的在途状态。队列里持有的都是旧 LevelChunk。
     *
     * <p>
     * 不复位 ENCODE_TASKS_IN_FLIGHT：旧世界可能有超时未收尾的编码任务仍在途，它们的出口会递减
     * 这个计数，先 set(0) 会被减成负数，下一个世界的关服等待就会误判成没有在途任务而直接放行。
     */
    public static void clearWorldState() {
        pendingEncode.clear();
        ENCODE_FAIL_LOGGED.set(0);
    }

    /** ChunkMap 没有 getChunks，改反射 visibleChunkMap。 */
    private static final Field F_VISIBLE_CHUNKS;

    static {
        try {
            F_VISIBLE_CHUNKS = ChunkMap.class.getDeclaredField("visibleChunkMap");
            F_VISIBLE_CHUNKS.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SectionLifecycle() {
    }

    // ---- 触发入队 ----

    /**
     * 扫描清理窗口之外、且不再有用途的 section，主线程。由 {@code FarlandsTick} 在窗口变化或上一轮未
     * 清完时调用。
     *
     * <p>
     * 不跳过在途 chunk：在途任务只写窗口内的段，而本方法从不删窗口内的段，所以两者无交集。原来整块
     * 跳过在途 chunk 是过粗的，而洪峰期间在途是常态，于是窗口外的段长期积压。
     *
     * <p>
     * 遍历用零余量，而余量只对「已经算出东西的段」成立，防的是边界抖动导致反复释放与重建。低于
     * LIGHTED 的段从未落盘，重建的代价只是一个 biome 网格加一次默认段构造，所以它不需要余量保护；而
     * 窗口外的低档段永远推进不了，且没有任何在途任务会碰它，它留在 SectionStage 里只会让装饰的门在写域
     * 上必失败。加余量遍历时，窗口外那一圈低档段整片落在余量带内，枚举不到，于是它们永不释放。
     *
     * @return 是否还有候选项没处理完，调用方据此续跑
     */
    public static boolean cleanup(MinecraftServer server) {
        cleanupOpen = false;
        int[] budget = { CLEANUP_BUDGET };
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                // 用 getLatestChunk 而非 getTickingChunk。暂停或登出后 chunk 会降级，
                // tickingChunkFuture 完成成 UNLOADED，getTickingChunk 返回 null 就漏遍历。
                // getLatestChunk 不依赖 ticking 状态，只要 chunk 在 holder 里就返回。
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                WindowedChunk wc = (WindowedChunk) lc;
                // 增量扫描，只遍历窗口之外的 section，成本是 O(log n + 窗口外数)
                wc.forEachOutsideWindows(0, sy -> {
                    int stage = SectionStage.getStage(lc, sy);
                    if (stage >= SectionStage.LIGHTED) {
                        // 已点亮：数据在盘上，删内存即可；脏的入队落盘。与余量无关。
                        if (!wc.isSectionDirty(sy)) {
                            removeFromMemory(lc, sy);
                        } else if (budget[0] > 0) {
                            pendingEncode.add(new EncodeUnit(lc, sy, true));
                            budget[0]--;
                        } else {
                            cleanupOpen = true; // 预算耗尽，剩下的下一轮再落
                        }
                        return;
                    }
                    // 低于 LIGHTED 的段从未落盘，persistChunkDirty 只写 >= LIGHTED 的段，所以释放不
                    // 丢数据。而窗口外意味着 collectSegments 收不到它、没有在途任务会碰它，留着它只会
                    // 让装饰的门在写域上必失败，待装饰表因此只涨不落。
                    removeFromMemory(lc, sy);
                });
            }
        }
        wakeEncodeConsumer();
        return cleanupOpen;
    }

    /**
     * 回收「档位表有、段容器没有」的残留条目。
     *
     * <p>不进 {@link #cleanup}：那一支每 tick 在窗口变化时对全部 chunk 跑，而这条只是清静态残留，
     * 200 tick 一遍就够。判据只用容器键集，与装饰写门同宽；在读的段跳过，读回结束会自行落档。
     *
     * <p>删除对记账是中性的：这类条目从未经过 {@code onMaterialize} 或 {@code onStageChange}，
     * 记账的已计入集合里没有它，{@code onRemove} 的第一步就会返回。
     */
    public static void reclaimOrphanStages(MinecraftServer server) {
        int reclaimed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                for (int sy : SectionStage.stagesWithoutSection(lc)) {
                    if (SectionIO.isReading(lc, sy)) {
                        continue;
                    }
                    SectionStage.removeStage(lc, sy);
                    reclaimed++;
                }
            }
        }
        StageSlotProbe.reclaimed(reclaimed);
    }

    /** 卸载编码在途任务数，关服等待用，任务出口递减。 */
    private static final AtomicInteger ENCODE_TASKS_IN_FLIGHT = new AtomicInteger();

    /**
     * 卸载编码独立线程池，单线程 daemon。与 genPool 隔离，避免编码抢生成线程。两者曾共用一个池，
     * 结果是 fill 被编码延迟，生成效率下降。
     */
    private static final ExecutorService ENCODE_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "farlands-encode");
        t.setDaemon(true);
        return t;
    });

    /**
     * 卸载时写该 chunk 的全部脏 section，由 ServerLevel.unload 之前调用。
     *
     * 生成或光照在途即 isChunkBusy 时跳过，半成品与未就绪光照不落盘。
     *
     * 编码提交到独立的 ENCODE_POOL，不占主线程预算、不阻塞卸载线程、不与生成抢线程。
     * flushChunk 返回后 LevelChunk 引用即释放，闭包只被编码任务短暂持有。并发约定：
     * cleanup 在主线程可能并发删除本 chunk 窗口外的 section，编码读到 null 或旧引用都无害，
     * 因为 cleanup 只删磁盘已有或已入队的 section，同一 section 的重复写也幂等。
     *
     * 编码任务出口顺手 SectionStage.clear。stage 载体是静态 map，chunk 卸载后必须清，
     * 否则随探索单调增长。
     */
    public static void flushChunk(LevelChunk lc) {
        ServerLevel level = (ServerLevel) lc.getLevel();
        // 起点标记、结构相的挂起项与装饰的待办项都随卸载一起丢：重载时起点由读盘填回、标记重算，
        // 装饰由下一次雕刻重新登记。卸载是它们唯一的权威移除点，tick 里取不到 chunk 不算。
        StructureDriver.clearChunk(level.dimension(), lc.getPos().pack());
        DecorationFiller.cellChanged(level, lc.getPos().pack());
        if (TerrainHooks.isChunkBusy(lc)) {
            // 忙则跳过编码，但状态必须清：清掉身份之后，在途任务随后的写口会空转，不会给同坐标的
            // 新一代 rebuild 出旧代的档位。跳过编码本身是既有取舍，重进时重生成兜底。
            SectionStage.clear(lc);
            return;
        }
        ENCODE_TASKS_IN_FLIGHT.incrementAndGet();
        try {
            ENCODE_POOL.submit(() -> {
                try {
                    encodeAndSubmit(lc, level);
                } finally {
                    ENCODE_TASKS_IN_FLIGHT.decrementAndGet();
                    // 卸载丢弃的段连同 stage 一起没了。
                    SectionStage.clear(lc);
                }
            });
        } catch (RejectedExecutionException e) {
            ENCODE_TASKS_IN_FLIGHT.decrementAndGet();
            // 池关闭，编码丢弃，数据由重进重生成兜底；状态同样要清，否则留到关服才由 clearAll 收。
            SectionStage.clear(lc);
        }
    }

    /**
     * 编码池线程：现取现编码该 chunk 的全部脏 section，再回主线程 prepareWrite 与
     * submitWrite。提交前验证 chunk 对象未变，若已重新加载为新对象就丢弃该编码，数据交给
     * 新 chunk 的写盘路径，防旧数据覆盖新数据。验证对象为 null 或仍是同一对象都提交。
     */
    private static void encodeAndSubmit(LevelChunk lc, ServerLevel level) {
        ChunkPos cp = lc.getPos();
        PalettedContainerFactory factory = ((WindowedChunk) lc).containerFactory();
        // path 到 (entry, slotIdx, sy)
        Map<Path, List<Object[]>> byFile = new LinkedHashMap<>();
        for (Integer sy : ((WindowedChunk) lc).windowedAllSections().keySet()) {
            if (!((WindowedChunk) lc).isSectionDirty(sy)) {
                continue;
            }
            byte[] entry = encodeNow(lc, sy, factory);
            if (entry == null) {
                continue;
            }
            Path path = SectionIO.filePath(level, cp.x(), cp.z(), sy);
            byFile.computeIfAbsent(path, k -> new ArrayList<>())
                    .add(new Object[] { entry, SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sy & 31), sy });
        }
        if (byFile.isEmpty()) {
            return;
        }
        SectionIO.runOnMainThread(() -> {
            LevelChunk now = latestChunk(level, cp.x(), cp.z());
            if (now != null && now != lc) {
                return; // 已重新加载为新对象，丢弃旧编码
            }
            for (Map.Entry<Path, List<Object[]>> e : byFile.entrySet()) {
                Path path = e.getKey();
                SectionStorage st = SectionIO.getOrOpen(path);
                List<SectionStorage.PendingWrite> batch = new ArrayList<>();
                for (Object[] meta : e.getValue()) {
                    SectionStorage.PendingWrite pw = st.prepareWrite((Integer) meta[1], (byte[]) meta[0]);
                    if (pw != null) {
                        batch.add(pw);
                    }
                }
                if (!batch.isEmpty()) {
                    // 卸载路径的 onAllDone 只 commit，不 clearDirty。chunk 已释放，
                    // dirty 随对象消亡。
                    SectionIO.submitWrite(level, path, batch, () -> {
                        SectionStorage st2 = SectionIO.getOrOpen(path);
                        for (SectionStorage.PendingWrite pw : batch) {
                            st2.commitWrite(pw);
                        }
                    });
                }
            }
        }, level);
    }

    /**
     * 关服：等卸载编码任务全部提交，有界。ENCODE_POOL 执行编码，再由 runOnMainThread 提交
     * prepareWrite 与 submitWrite，因此循环内要反复 drain 主线程来消费提交回调。超时后
     * 未提交的编码丢弃，由重进重生成兜底。必须在 awaitIODrain 之前调用，否则编码任务提交的
     * IO 写不会被等待。
     */
    public static void awaitEncodeTasks(MinecraftServer server, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && ENCODE_TASKS_IN_FLIGHT.get() > 0) {
            SectionIO.drainMainThreadTasks(server);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 光照完成后的补触发，把该 chunk 的脏 section 入队 PERSIST。此时 fill 与光照都已完成，
     * 数据完整。调用点保证不 busy，因此无条件入队，幂等。调用点是 GenQueue.triggerLight 的
     * lightChunk whenComplete。
     */
    public static void persistChunkDirty(LevelChunk lc) {
        enqueueDirty(lc);
    }

    /** 该 chunk 的全部脏 section 入队 PERSIST。只存引用，主线程 tick 现取现编码。 */
    private static void enqueueDirty(LevelChunk lc) {
        WindowedChunk wc = (WindowedChunk) lc;
        for (Integer sy : wc.windowedAllSections().keySet()) {
            if (wc.isSectionDirty(sy)) {
                pendingEncode.add(new EncodeUnit(lc, sy, false));
            }
        }
        wakeEncodeConsumer();
    }

    /**
     * 周期持久化，每 fsaPersistInterval tick 一次。主线程把全部已加载 chunk 的脏 section
     * 入队 PERSIST。
     */
    public static void flushAllDirty(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                if (TerrainHooks.isChunkBusy(lc)) {
                    continue; // 生成或光照在途，跳过，改由光照完成补触发 persistChunkDirty 写盘
                }
                WindowedChunk wc = (WindowedChunk) lc;
                for (Integer sy : wc.windowedAllSections().keySet()) {
                    // 同 cleanup：低于 LIGHTED 的段此刻不写盘，等光照补触发后的 persistChunkDirty。
                    if (wc.isSectionDirty(sy) && SectionStage.getStage(lc, sy) >= SectionStage.LIGHTED) {
                        pendingEncode.add(new EncodeUnit(lc, sy, false));
                    }
                }
            }
        }
        wakeEncodeConsumer();
    }

    // ---- 编码消费：池驱动 ----

    /** 编码消费者是否已在池上跑。CAS 保证同时只有一份 drain。 */
    private static final AtomicBoolean encodeConsumerActive = new AtomicBoolean();

    private static void wakeEncodeConsumer() {
        if (encodeConsumerActive.compareAndSet(false, true)) {
            try {
                ENCODE_POOL.submit(SectionLifecycle::drainEncode);
            } catch (RejectedExecutionException e) {
                encodeConsumerActive.set(false); // 池已关闭，即关服，丢弃
            }
        }
    }

    /** 池线程产出的编码结果，主线程据此记账。 */
    private record Encoded(LevelChunk chunk, int sectionY, boolean cleanup, byte[] entry) {
    }

    /**
     * 一轮 drain 最多编码的段数。编满就交接并立刻再唤醒一轮，把主线程每次吃的量钉在约 0.4 毫秒
     * 以内；超过这个量的批次会让 map 分配与 LRU 命中率一起变差。
     */
    private static final int ENCODE_PASS_BUDGET = 256;

    /**
     * 池线程：出队、判写入者、编码，再按维度归批投回主线程记账。
     *
     * <p>
     * 编码搬到这里，依据是 encodeNow 自己的线程约定：主线程 tick 与编码池卸载共用，任意线程安全。
     * 主线程只剩 getOrOpen、prepareWrite、commitWrite 与扇区分配，那些是主线程独占状态，不能搬。
     *
     * <p>
     * 整轮最多处理进入时队列长度那么多的单元，保证本轮必然结束。不满足写入者判据的单元放回队尾，
     * 留到下一次唤醒。
     */
    private static void drainEncode() {
        boolean more = false;
        try {
            Map<ServerLevel, List<Encoded>> byLevel = new LinkedHashMap<>();
            int remaining = Math.min(pendingEncode.size(), ENCODE_PASS_BUDGET);
            while (remaining-- > 0) {
                EncodeUnit u = pendingEncode.poll();
                if (u == null) {
                    break;
                }
                // 生成或群系在途：段内容还没定，此刻序列化会撞 PalettedContainer 的多线程检测。
                if (TerrainHooks.isChunkBusy(u.chunk()) || TerrainHooks.isBiomeFilling(u.chunk())) {
                    pendingEncode.addLast(u);
                    continue;
                }
                PalettedContainerFactory factory = ((WindowedChunk) u.chunk()).containerFactory();
                byte[] entry = encodeNow(u.chunk(), u.sectionY(), factory);
                if (entry == null) {
                    continue; // encode 失败或低于 LIGHTED，丢弃，dirty 保留，下次触发会重入队
                }
                if (!(u.chunk().getLevel() instanceof ServerLevel level)) {
                    continue;
                }
                byLevel.computeIfAbsent(level, k -> new ArrayList<>())
                        .add(new Encoded(u.chunk(), u.sectionY(), u.cleanup(), entry));
            }
            for (Map.Entry<ServerLevel, List<Encoded>> e : byLevel.entrySet()) {
                ServerLevel level = e.getKey();
                List<Encoded> encoded = e.getValue();
                // 计数只覆盖到提交为止：后面的数据写由 awaitIODrain 的屏障负责等。
                ENCODE_TASKS_IN_FLIGHT.incrementAndGet();
                SectionIO.runOnMainThread(() -> applyEncoded(level, encoded), level);
            }
            // 只在本轮真有产出、且队列仍非空时续唤醒。全被写入者挡回时不能自旋，那些单元由每 tick 的
            // tick() 兜底唤醒。唤醒放在 finally 之后，否则 CAS 会因本消费者仍在跑而失败。
            more = !byLevel.isEmpty() && !pendingEncode.isEmpty();
        } finally {
            encodeConsumerActive.set(false);
        }
        if (more) {
            wakeEncodeConsumer();
        }
    }

    /**
     * 主线程：把池上编好的结果记账并提交写批。onAllDone 的语义与搬池前一致：cleanup 是提交后删内存，
     * persist 是提交后清脏。
     */
    private static void applyEncoded(ServerLevel level, List<Encoded> encoded) {
        try {
            Map<Path, List<SectionStorage.PendingWrite>> cleanupByFile = new LinkedHashMap<>();
            Map<Path, List<CleanupUnit>> cleanupUnits = new LinkedHashMap<>();
            Map<Path, List<SectionStorage.PendingWrite>> persistByFile = new LinkedHashMap<>();
            Map<Path, List<CleanupUnit>> persistUnits = new LinkedHashMap<>();
            Map<Path, ServerLevel> levelByPath = new HashMap<>();
            for (Encoded e : encoded) {
                ChunkPos cp = e.chunk().getPos();
                Path path = SectionIO.filePath(level, cp.x(), cp.z(), e.sectionY());
                SectionStorage st = SectionIO.getOrOpen(path);
                SectionStorage.PendingWrite pw = st.prepareWrite(
                        SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, e.sectionY() & 31), e.entry());
                if (pw == null) {
                    continue;
                }
                levelByPath.put(path, level);
                if (e.cleanup()) {
                    cleanupByFile.computeIfAbsent(path, k -> new ArrayList<>()).add(pw);
                    cleanupUnits.computeIfAbsent(path, k -> new ArrayList<>())
                            .add(new CleanupUnit(e.chunk(), e.sectionY()));
                } else {
                    persistByFile.computeIfAbsent(path, k -> new ArrayList<>()).add(pw);
                    persistUnits.computeIfAbsent(path, k -> new ArrayList<>())
                            .add(new CleanupUnit(e.chunk(), e.sectionY()));
                }
            }
            submitBatches(levelByPath, cleanupByFile, cleanupUnits, true);
            submitBatches(levelByPath, persistByFile, persistUnits, false);
        } finally {
            ENCODE_TASKS_IN_FLIGHT.decrementAndGet();
        }
    }

    /**
     * 关服同步兜底，把仍脏的 section 同步 encode、prepare、doWrite、commit 并同步刷盘。
     * 调用方已 awaitIODrain，IO 队列为空，再加上 drainMainThreadTasks 让 commit 回调执行完，
     * 因此本方法只处理屏障之后新标脏的，比较罕见。全部在主线程同步完成，不依赖异步回调。
     */
    public static void shutdownSyncFlush(MinecraftServer server) {
        Map<Path, List<SectionStorage.PendingWrite>> byFile = new LinkedHashMap<>();
        Map<Path, List<CleanupUnit>> unitsByFile = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                // 用 getLatestChunk 而非 getTickingChunk。关服或暂停后 chunk 降级为非 TICKING，
                // getTickingChunk 返回 null 就漏写盘，这是数据丢的根因。getLatestChunk 不依赖
                // ticking。
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                if (TerrainHooks.isChunkBusy(lc)) {
                    continue; // 关服等待超时后仍有在途，跳过，重进重生成兜底
                }
                WindowedChunk wc = (WindowedChunk) lc;
                PalettedContainerFactory factory = wc.containerFactory();
                for (Integer sy : wc.windowedAllSections().keySet()) {
                    if (!wc.isSectionDirty(sy)) {
                        continue;
                    }
                    byte[] entry = encodeNow(lc, sy, factory); // PERSIST 语义
                    if (entry == null) {
                        continue;
                    }
                    ChunkPos cp = lc.getPos();
                    Path path = SectionIO.filePath(level, cp.x(), cp.z(), sy);
                    SectionStorage st = SectionIO.getOrOpen(path);
                    SectionStorage.PendingWrite pw = st.prepareWrite(
                            SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sy & 31), entry);
                    if (pw != null) {
                        byFile.computeIfAbsent(path, k -> new ArrayList<>()).add(pw);
                        unitsByFile.computeIfAbsent(path, k -> new ArrayList<>())
                                .add(new CleanupUnit(lc, sy));
                    }
                }
            }
        }
        // 同步写，即主线程直接 file.write，再 commit 与清 dirty
        for (Map.Entry<Path, List<SectionStorage.PendingWrite>> e : byFile.entrySet()) {
            Path path = e.getKey();
            SectionStorage st = SectionIO.getOrOpen(path);
            try {
                st.doWrite(e.getValue());
            } catch (Exception ex) {
                InfsFarlands.LOGGER.error("fsa shutdown write failed {}", path, ex);
                continue;
            }
            for (SectionStorage.PendingWrite pw : e.getValue()) {
                st.commitWrite(pw);
            }
            for (CleanupUnit u : unitsByFile.get(path)) {
                ((WindowedChunk) u.chunk()).clearSectionDirty(u.sectionY());
            }
        }
        SectionIO.flushAllSync();
    }

    /**
     * 提交写批。onAllDone 按 mode 决定后续动作，cleanup 是 commit 后删内存，
     * persist 是 commit 后清 dirty。
     */
    private static void submitBatches(Map<Path, ServerLevel> levelByPath,
            Map<Path, List<SectionStorage.PendingWrite>> byFile,
            Map<Path, List<CleanupUnit>> unitsByFile, boolean cleanup) {
        for (Map.Entry<Path, List<SectionStorage.PendingWrite>> e : byFile.entrySet()) {
            Path path = e.getKey();
            List<SectionStorage.PendingWrite> batch = e.getValue();
            List<CleanupUnit> units = unitsByFile.get(path);
            SectionIO.submitWrite(levelByPath.get(path), path, batch, () -> {
                SectionStorage st = SectionIO.getOrOpen(path);
                for (SectionStorage.PendingWrite pw : batch) {
                    st.commitWrite(pw);
                }
                if (units != null) {
                    for (CleanupUnit u : units) {
                        if (cleanup) {
                            removeFromMemory(u.chunk(), u.sectionY());
                        } else {
                            ((WindowedChunk) u.chunk()).clearSectionDirty(u.sectionY());
                        }
                    }
                }
            });
        }
    }

    private static final AtomicInteger ENCODE_FAIL_LOGGED = new AtomicInteger();

    /**
     * 现取现编码单个 section，等待期间的变化会被捕捉。主线程关服兜底与编码池共用，
     * 只做纯读加局部对象，任意线程安全。碰调色板容器的那一小段与主线程写方块、以及另外四个
     * 打包点用 SectionSerializer.packLockFor 互斥，锁取在 SectionSerializer.encode 内部。
     */
    private static byte[] encodeNow(LevelChunk lc, int sy, PalettedContainerFactory factory) {
        try {
            LevelChunkSection section = ((WindowedChunk) lc).windowedAllSections().get(sy);
            if (section == null) {
                return null;
            }
            ServerLevel level = (ServerLevel) lc.getLevel();
            LevelLightEngine le = level.getChunkSource().getLightEngine();
            DataLayer bl = le.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(lc.getPos(), sy));
            DataLayer sl = le.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(lc.getPos(), sy));
            int stage = SectionStage.getStage(lc, sy);
            // 兜底不变量：fsa 里只允许写入 LIGHTED。低于它的段这时仍在内存里且仍脏，
            // 光照补触发完成后 persistChunkDirty 会重新入队；写下去等于把没点亮的状态固化到盘上，
            // 重进时恢复出低于 LIGHTED 的段，又得补一次光照。
            if (stage < SectionStage.LIGHTED) {
                return null;
            }
            return SectionSerializer.encode(section, bl, sl, stage, factory, sy, lc.getPos().pack());
        } catch (Exception e) {
            // encode 失败就静默返回，数据不写盘也就是丢失，但 dirty 保留，之后重试
            if (ENCODE_FAIL_LOGGED.getAndIncrement() < 20) {
                InfsFarlands.LOGGER.info("ENCODE-FAIL chunk={},{} sy={} err={}",
                        lc.getPos().x(), lc.getPos().z(), sy, e.toString());
            }
            return null;
        }
    }

    /** 删内存，包括 section、stage、脏标记与光照层，主线程执行。窗口内的不删。 */
    private static void removeFromMemory(LevelChunk lc, int sy) {
        if (EntitySectionWindow.inAnyWindow(lc.getLevel().dimension(), sy)) {
            return;
        }
        WindowedChunk wc = (WindowedChunk) lc;
        wc.windowedAllSections().remove(sy);
        wc.removeActiveSection(sy);
        SectionStage.removeStage(lc, sy);
        wc.clearSectionDirty(sy);
        if (lc.getLevel() instanceof ServerLevel sl
                && sl.getChunkSource().getLightEngine() instanceof FarLandsLightEngine fle) {
            SectionPos pos = SectionPos.of(lc.getPos(), sy);
            fle.removeSectionData(LightLayer.BLOCK, pos);
            fle.removeSectionData(LightLayer.SKY, pos);
        }
    }

    // ---- 读回 ----

    /**
     * chunk 加载后读回窗口内的 section，主线程执行，全部完成后跑 onDone，随后可入生成队列。
     *
     * 调用点是 terrain 的 chunk 短路链，即 GenerationChunkHolderMixin 的 existence flow：
     * 建空壳，后台填 biome，回主线程调 loadChunkSections，完成后 enqueueChunk。单 section 读回
     * 走 loadSection，由窗口滑入触发。
     */
    public static void loadChunkSections(LevelChunk lc, Runnable onDone) {
        ServerLevel level = (ServerLevel) lc.getLevel();
        ChunkPos cp = lc.getPos();
        PalettedContainerFactory factory = ((WindowedChunk) lc).containerFactory();
        List<Integer> windowSy = new ArrayList<>();
        EntitySectionWindow.forEachSectionInAnyWindow(lc.getLevel().dimension(), windowSy::add);
        if (windowSy.isEmpty()) {
            // 窗口为空时用面的段带顶上：世界创建期的阻塞取数发生在玩家进名单之前，那时既没有窗口
            // 也没有玩家，但出生区已经被登记成一个面，读回范围从那里取。
            int[] faceRange = Scheduler.sectionRangeAt(level, cp);
            if (faceRange != null) {
                for (int sy = faceRange[0]; sy <= faceRange[1]; sy++) {
                    if (WorldBounds.inSection(sy)) {
                        windowSy.add(sy);
                    }
                }
            } else if (level.players().isEmpty()) {
                // 该维度确实没有玩家、也没有面：没有读回范围，onDone 照常执行，不会生成。窗口建立后
                // 由 ChunkDataSender 的单段读回补上，那里置闩。
                onDone.run();
                return;
            } else {
                // 该维度有玩家而窗口表为空：窗口在 tick 末尾才更新，而阻塞的 FULL 取数发生在 tick 之内，
                // 玩家刚进入的维度此刻在表里还没有条目。这里按该维度自己的玩家就地算带，走正常读回。
                windowSy.addAll(windowSectionsFromPlayers(level));
            }
        }
        // 窗口读回发起即置闩：闩的含义是「这个 chunk 的窗口面读过一次盘了」。
        ChunkCounters.markWindowReadDone(lc);
        SectionIO.markReadingBatch(lc, windowSy);

        Map<Path, List<SectionStorage.SlotRef>> byFile = new LinkedHashMap<>();
        Map<Path, ServerLevel> levelByPath = new HashMap<>();
        for (int sy : windowSy) {
            Path path = SectionIO.filePath(level, cp.x(), cp.z(), sy);
            if (!Files.exists(path)) {
                continue; // 无文件即无数据，不创建
            }
            SectionStorage st = SectionIO.getOrOpen(path);
            SectionStorage.SlotRef ref = st.getSlot(
                    SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sy & 31), sy);
            if (ref != null) {
                byFile.computeIfAbsent(path, k -> new ArrayList<>()).add(ref);
                levelByPath.put(path, level);
            }
        }
        if (byFile.isEmpty()) {
            SectionIO.unmarkReadingBatch(lc, windowSy);
            onDone.run();
            return;
        }
        AtomicInteger pending = new AtomicInteger(byFile.size());
        for (Map.Entry<Path, List<SectionStorage.SlotRef>> e : byFile.entrySet()) {
            SectionIO.submitRead(levelByPath.get(e.getKey()), e.getKey(), e.getValue(), factory,
                    decodedList -> {
                        for (SectionIO.DecodedWithSy d : decodedList) {
                            applyDecoded(lc, d.sectionY(), d.decoded());
                        }
                        if (pending.decrementAndGet() <= 0) {
                            SectionIO.unmarkReadingBatch(lc, windowSy);
                            ChunkCounters.markWindowReadDone(lc);
                            promotePendingSections(lc, windowSy);
                            skySourcesReady(lc);
                            Scheduler.submit("read", lc);
                            onDone.run();
                        }
                    });
        }
    }

    /**
     * 本 tick 还没更新的维度的窗口带，按该维度自己的玩家就地算。
     *
     * <p>只在该维度有玩家、而窗口表里没有它的条目时调用，形状与 {@code EntitySectionWindow.update}
     * 对单玩家算的那一段逐字相同，区别只是不写回表：表由 tick 末尾统一重建。
     */
    private static List<Integer> windowSectionsFromPlayers(ServerLevel level) {
        int half = FarlandsConfig.verticalSimulationDistance;
        TreeSet<Integer> sections = new TreeSet<>();
        for (ServerPlayer player : level.players()) {
            int center = Mth.floorDiv(player.getBlockY(), 16);
            for (int sy = center - half; sy <= center + half; sy++) {
                sections.add(sy);
            }
        }
        return new ArrayList<>(sections);
    }

    /**
     * 预加载的范围读回：范围内每段发起读回。预加载期间没有玩家窗口，所以这里直接置闩：闩的含义是
     * 「这个 chunk 的窗口面读过一次盘了」，预加载的范围读回等价于一次窗口读回。
     */
    public static void preloadRange(LevelChunk lc, int minSy, int maxSy) {
        ChunkCounters.markWindowReadDone(lc);
        for (int sy = minSy; sy <= maxSy; sy++) {
            if (!SectionIO.isReading(lc, sy)) {
                loadSection(lc, sy, () -> {
                });
            }
        }
    }

    /**
     * 窗口滑入单个 section 的读回，主线程执行，完成后跑 onDone，随后可入生成队列。
     * 调用点是 ChunkDataSender.enqueueForWindow，即窗口差量检测。
     */
    public static void loadSection(LevelChunk lc, int sectionY, Runnable onDone) {
        SectionIO.markReading(lc, sectionY);
        // 单段读回由窗口滑入触发，窗口面已建立，置闩。
        ChunkCounters.markWindowReadDone(lc);
        ServerLevel level = (ServerLevel) lc.getLevel();
        ChunkPos cp = lc.getPos();
        Path path = SectionIO.filePath(level, cp.x(), cp.z(), sectionY);
        boolean exist = Files.exists(path);
        if (!exist) {
            SectionIO.unmarkReading(lc, sectionY);
            // 读回在途这一条落了，且没有数据要恢复：该 chunk 可能就此就绪，交准入。
            Scheduler.submit("read", lc);
            onDone.run();
            return;
        }
        SectionStorage st = SectionIO.getOrOpen(path);
        SectionStorage.SlotRef ref = st.getSlot(
                SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sectionY & 31), sectionY);
        if (ref == null) {
            SectionIO.unmarkReading(lc, sectionY);
            // 同上：无槽位即无数据，读回在途这一条落，交准入。
            Scheduler.submit("read", lc);
            onDone.run();
            return;
        }
        PalettedContainerFactory factory = ((WindowedChunk) lc).containerFactory();
        SectionIO.submitRead(level, path, List.of(ref), factory, decodedList -> {
            SectionIO.unmarkReading(lc, sectionY);
            for (SectionIO.DecodedWithSy d : decodedList) {
                applyDecoded(lc, d.sectionY(), d.decoded());
            }
            promotePendingSections(lc, List.of(sectionY));
            skySourcesReady(lc);
            Scheduler.submit("read", lc);
            onDone.run();
        });
    }

    /**
     * 读回收尾的晋升：把刚就位那些段的待建方块实体标签促其晋升。
     *
     * <p>
     * 标签由 vanilla 的 pendingBlockEntities 承载，消费门在 serialize/LevelChunkMixin 里，判据是段级就绪。
     * 本方法必须排在 unmark 之后而不是 applyDecoded 里：applyDecoded 执行期间该段仍标着在读，会被那道门挡回。
     * 段未就位的标签在这里被拒是正常的，留给它自己落位后的下一轮。
     */
    private static void promotePendingSections(LevelChunk lc, List<Integer> sectionYs) {
        PendingBlockEntities host = (PendingBlockEntities) lc;
        for (int sy : sectionYs) {
            host.promotePendingBlockEntities(sy);
        }
    }

    /**
     * 读回完成时触发。清理 SKY_WAITERS 里等待该 chunk 的条目，并触发邻居边界重播。
     * 读回的 chunk 里 SkyLightSources 未 fillFrom，source 全为 minY，于是 boundaryAllOpen
     * 为 false，会触发重播。重播用的伪源与播种时一致，不新增错误。读回每 chunk 一次，
     * 属低频。
     */
    private static void skySourcesReady(LevelChunk lc) {
        if (lc.getLevel() instanceof ServerLevel sl
                && sl.getChunkSource().getLightEngine() instanceof FarLandsLightEngine fle) {
            fle.onChunkSkySourcesReady(lc.getPos());
        }
    }

    /**
     * 读回结果应用，主线程。写入数据与光照，恢复 stage，并标记该 chunk 需要补发 section 包。
     * 读回不标脏，因为磁盘上已经有了。
     */
    private static void applyDecoded(LevelChunk lc, int sy, SectionSerializer.DecodedSection decoded) {
        WindowedChunk wc = (WindowedChunk) lc;
        wc.windowedAllSections().put(sy, decoded.section());
        wc.addActiveSection(sy);
        // 读回是第三条物化路径，绕开 ChunkAccess.getSection，所以记账要在这里补一次。随后的
        // setStage 会把该段从 UNPROCESSED 推到盘上档位，两侧合计只记一次。
        ChunkCounters.onMaterialize(lc, sy);
        lc.getSection(lc.getSectionIndexFromSectionY(sy)); // 数组同步，get 内部会执行 arr[idx]=s
        if (lc.getLevel() instanceof ServerLevel sl) {
            LevelLightEngine le = sl.getChunkSource().getLightEngine();
            SectionPos pos = SectionPos.of(lc.getPos(), sy);
            if (decoded.blockLight() != null) {
                le.queueSectionData(LightLayer.BLOCK, pos, decoded.blockLight());
            }
            if (decoded.skyLight() != null) {
                le.queueSectionData(LightLayer.SKY, pos, decoded.skyLight());
            }
        }
        CarveLoopProbe.restore(lc, sy, decoded.stage());
        // 读回按盘上档恢复，可能低于内存里的当前档，所以走无条件的恢复入口而不是单调闸那一支。
        Scheduler.restore(lc, new int[] { sy }, decoded.stage());
        if (decoded.stage() >= SectionStage.LIGHTED) {
            // 读回不推进档位，但它的 LIGHTED 要计进吞吐，否则重进的 chunk 不进 CPS 与 SPS。
            StageMetrics.lightedSections(1);
        }
        // 这一段刚被读回恢复，档位来自磁盘：它的邻居门要重判。
        DecorationFiller.cellChanged((ServerLevel) lc.getLevel(), lc.getPos().pack());
        StructureDriver.cellChanged();
        // 磁盘只存 fsa 的方块与光照，chunk NBT 的 sections 被剥空，所以原版读盘路径里那句
        // poiManager.checkConsistencyWithBlocks 在本 port 从不执行。恢复的段在这里补一次，
        // 清掉方块已不存在的 POI 条目。
        if (lc.getLevel() instanceof ServerLevel poiLevel) {
            poiLevel.getPoiManager().checkConsistencyWithBlocks(SectionPos.of(lc.getPos(), sy),
                    decoded.section());
        }
        ChunkDataSender.markChunkChanged(lc);
    }

    // ---- 工具 ----

    /** 自检一行最多列几个失配 chunk。 */
    private static final int VERIFY_LOG_LIMIT = 8;

    /**
     * 派生记账的自检：逐 chunk 重数一遍段状态与段容器，与 {@link ChunkCounters} 的记录比对。只报
     * 失配，不给解读。
     *
     * <p>重数的口径是「段容器与段状态表的并集」，因为非噪声生成器的维度里档位会领先于段。比对的五
     * 个量与记账的五个量一一对应：低于 SURFACE 的段数、低于 DECORATED 的段数、低于 TERRAIN 的
     * 最高段号、等于 DECORATED 的段数、低于 LIGHTED 的段数。
     *
     * <p>主线程调用，重数期间写点仍可能在池上推进，所以单次失配不等于错，按整窗的出现频率判断。
     */
    public static void verifyCounters(MinecraftServer server) {
        int checked = 0;
        int skipped = 0;
        int mismatched = 0;
        int carversTotal = 0;
        int decoratedTotal = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                // 已交给卸载流程的 chunk：身份已注销、记账按设计清掉，而它的段容器还在。重数只会拿
                // 「容器视为全 UNPROCESSED」去对「全零」，报出来的失配没有意义。判据用我们自己的存活位，
                // 因为 26.1.2 没有 isLoaded() 读取口。
                if (!CounterIdentities.owned(lc)) {
                    skipped++;
                    continue;
                }
                checked++;
                IntOpenHashSet universe = new IntOpenHashSet();
                universe.addAll(((WindowedChunk) lc).windowedAllSections().keySet());
                SectionStage.forEachStage(lc, (sy, stage) -> universe.add(sy.intValue()));
                int pendingSurface = 0;
                int pendingDecorated = 0;
                int cursor = ChunkCounters.NO_CURSOR;
                int carversHere = 0;
                int decoratedHere = 0;
                for (int sy : universe) {
                    int stage = SectionStage.getStage(lc, sy);
                    if (stage < SectionStage.SURFACE) {
                        pendingSurface++;
                    }
                    if (stage < SectionStage.DECORATED) {
                        pendingDecorated++;
                    }
                    if (stage == SectionStage.CARVERS) {
                        carversHere++;
                    }
                    if (stage == SectionStage.DECORATED) {
                        decoratedHere++;
                    }
                    if (stage < SectionStage.TERRAIN && sy > cursor) {
                        cursor = sy;
                    }
                }
                carversTotal += carversHere;
                decoratedTotal += decoratedHere;
                ChunkCounters.Snapshot stored = ChunkCounters.snapshot(lc);
                if (stored.pendingSurface() != pendingSurface || stored.pendingDecorated() != pendingDecorated
                        || stored.terrainCursor() != cursor) {
                    mismatched++;
                    if (mismatched <= VERIFY_LOG_LIMIT) {
                        InfsFarlands.LOGGER.info(
                                "FLCOUNT mismatch chunk={},{} stored(sur={},dec={},cur={}) counted(sur={},dec={},cur={})",
                                lc.getPos().x(), lc.getPos().z(),
                                stored.pendingSurface(), stored.pendingDecorated(), stored.terrainCursor(),
                                pendingSurface, pendingDecorated, cursor);
                    }
                }
            }
        }
        InfsFarlands.LOGGER.info("FLCOUNT checked={} skipped={} mismatched={} carvers={} decorated={}",
                checked, skipped, mismatched, carversTotal, decoratedTotal);
    }

    /** 依赖表自检每轮最多查几个 chunk。 */
    private static final int DEP_VERIFY_SAMPLE = 64;

    /** 依赖表自检的轮转起点。 */
    private static int depVerifyStart;

    /**
     * 依赖表与票位的自检，只读。
     *
     * <p>可比的两行直接调今天的公开判据比对：SURFACE 与 CARVERS。TERRAIN、LIGHTED 与 FULL 三行没有
     * 独立写法的旧判据可比，只报表的读数，不计失配。装饰两行的旧判据已随待办表删除，不再比对。
     *
     * <p>票位读数按到最近玩家的距离分四桶，每桶报 chunk 数、票位级的区间与其中允许 FULL 的个数。
     * 分桶而不是按允许状态分带，是因为要核的是「距离决定票位」这条链：{@code visibleChunkMap} 的
     * 迭代序与加载顺序相关，按允许状态分带会让同一带的样本挤在一起，边界那几圈永远采不到。
     *
     * <p>主线程调用，每轮每维度按步长取最多 64 个样本，步长是 {@code size / 64}，起点逐轮推进。取步长
     * 而不是取连续一段：连续一段落在同一圈 chunk 上，读数的波动会全部来自区域差异，看不出边界。
     */
    public static void verifyDependencies(MinecraftServer server) {
        int checked = 0;
        int mismatched = 0;
        int logged = 0;
        int[] bucket = new int[4];
        int[] bucketLevelMin = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
        int[] bucketLevelMax = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        int[] bucketFull = new int[4];
        int terrainPending = 0;
        int lightedPending = 0;
        int fullPending = 0;
        boolean sampled = false;
        for (ServerLevel level : server.getAllLevels()) {
            List<ChunkHolder> holders = new ArrayList<>();
            for (ChunkHolder holder : getChunks(level)) {
                holders.add(holder);
            }
            if (holders.isEmpty()) {
                continue;
            }
            int view = DependencyTable.viewDistance(level);
            ChunkCursor cursor = cursor(level);
            int step = Math.max(1, holders.size() / DEP_VERIFY_SAMPLE);
            depVerifyStart++;
            for (int index = Math.floorMod(depVerifyStart, step); index < holders.size(); index += step) {
                ChunkAccess ca = holders.get(index).getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                // 同 verifyCounters：已交给卸载流程的 chunk 不参与比对。
                if (!CounterIdentities.owned(lc)) {
                    continue;
                }
                checked++;

                int surfaceTable = DependencyTable.pendingCount(lc, DependencyTable.SURFACE);
                boolean surfaceLegacy = SurfaceFiller.hasSurfacePending(lc);
                if ((surfaceTable > 0) != surfaceLegacy) {
                    mismatched++;
                    if (logged++ < VERIFY_LOG_LIMIT) {
                        InfsFarlands.LOGGER.info(
                                "FLDEP mismatch dimension={} chunk={},{} row=SURFACE legacy={} table={} tableCount={}",
                                level.dimension().identifier(), lc.getPos().x(), lc.getPos().z(), surfaceLegacy,
                                surfaceTable > 0, surfaceTable);
                    }
                }
                int carversTable = DependencyTable.pendingCount(lc, DependencyTable.CARVERS);
                boolean carversLegacy = CarverFiller.hasCarversPending(lc);
                if ((carversTable > 0) != carversLegacy) {
                    mismatched++;
                    if (logged++ < VERIFY_LOG_LIMIT) {
                        InfsFarlands.LOGGER.info(
                                "FLDEP mismatch dimension={} chunk={},{} row=CARVERS legacy={} table={} tableCount={}",
                                level.dimension().identifier(), lc.getPos().x(), lc.getPos().z(), carversLegacy,
                                carversTable > 0, carversTable);
                    }
                }
                if (DependencyTable.pendingCount(lc, DependencyTable.TERRAIN) > 0) {
                    terrainPending++;
                }
                if (DependencyTable.pendingCount(lc, DependencyTable.LIGHTED) > 0) {
                    lightedPending++;
                }
                if (DependencyTable.pendingCount(lc, DependencyTable.FULL) > 0) {
                    fullPending++;
                }

                int ticketLevel = cursor.ticketLevelAt(lc.getPos().x(), lc.getPos().z());
                int distance = DependencyTable.nearestPlayerDistance(level, lc.getPos().x(), lc.getPos().z());
                int which = distanceBucket(distance, view);
                bucket[which]++;
                if (ticketLevel < bucketLevelMin[which]) {
                    bucketLevelMin[which] = ticketLevel;
                }
                if (ticketLevel > bucketLevelMax[which]) {
                    bucketLevelMax[which] = ticketLevel;
                }
                if (DependencyTable.fullAllowed(ticketLevel)) {
                    bucketFull[which]++;
                }
                if (!sampled) {
                    sampled = true;
                    InfsFarlands.LOGGER.info(
                            "FLTICKET sample dimension={} chunk={},{} level={} status={} allowedStage={} full={} dist={} band={} view={}",
                            level.dimension().identifier(), lc.getPos().x(), lc.getPos().z(), ticketLevel,
                            ChunkLevel.generationStatus(ticketLevel), DependencyTable.allowedStage(ticketLevel),
                            DependencyTable.fullAllowed(ticketLevel), distance, DependencyTable.bandOf(ticketLevel),
                            view);
                }
            }
        }
        InfsFarlands.LOGGER.info(
                "FLDEP checked={} mismatched={} terrainPending={} lightedPending={} fullPending={}",
                checked, mismatched, terrainPending, lightedPending, fullPending);
        InfsFarlands.LOGGER.info(
                "FLTICKET chunks={} b0<=view: n={} level=[{},{}] full={} b1<=view+2: n={} level=[{},{}] full={} "
                        + "b2<=view+10: n={} level=[{},{}] full={} b3>view+10: n={} level=[{},{}] full={}",
                checked,
                bucket[0], levelMin(bucketLevelMin[0]), levelMax(bucketLevelMax[0]), bucketFull[0],
                bucket[1], levelMin(bucketLevelMin[1]), levelMax(bucketLevelMax[1]), bucketFull[1],
                bucket[2], levelMin(bucketLevelMin[2]), levelMax(bucketLevelMax[2]), bucketFull[2],
                bucket[3], levelMin(bucketLevelMin[3]), levelMax(bucketLevelMax[3]), bucketFull[3]);
    }

    /**
     * 距离分桶，取自票圈的形状：0 是视距内，1 是视距加 2 以内，2 是视距加 10 以内，3 是更远。
     * 视距加 10 是本 port 那颗保加载大票的半径。
     */
    private static int distanceBucket(int distance, int view) {
        if (distance <= view) {
            return 0;
        }
        if (distance <= view + 2) {
            return 1;
        }
        return distance <= view + 10 ? 2 : 3;
    }

    /** 空桶的票位级区间端点。 */
    private static int levelMin(int value) {
        return value == Integer.MAX_VALUE ? -1 : value;
    }

    private static int levelMax(int value) {
        return value == Integer.MIN_VALUE ? -1 : value;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<ChunkHolder> getChunks(ServerLevel level) {
        try {
            Object visible = F_VISIBLE_CHUNKS.get(level.getChunkSource().chunkMap);
            return ((Long2ObjectLinkedOpenHashMap<ChunkHolder>) visible).values();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 非阻塞取该位置当前的 LevelChunk。存在流程走过就有，没走过或只有 proto 时返回 null。
     *
     * <p>
     * 只在主线程调：visibleChunkMap 是裸的 Long2ObjectLinkedOpenHashMap，不是线程安全的。
     * getLatestChunk 本身不阻塞，FULL 的 future 被数据就绪悬着时会回退到 SPAWN 那个持 LevelChunk
     * 的 future，所以这里拿到的是壳而不是等数据。
     */
    @SuppressWarnings("unchecked")
    public static LevelChunk latestChunk(ServerLevel level, int cx, int cz) {
        try {
            Object visible = F_VISIBLE_CHUNKS.get(level.getChunkSource().chunkMap);
            ChunkHolder holder = ((Long2ObjectLinkedOpenHashMap<ChunkHolder>) visible).get(ChunkPos.pack(cx, cz));
            return holder != null && holder.getLatestChunk() instanceof LevelChunk lc ? lc : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 批量取数的游标：把 {@code visibleChunkMap} 的反射读做一次，之后每次 {@link #at} 只做 map get 与
     * {@code getLatestChunk()}。语义与 {@link #latestChunk} 逐行同源，只差反射次数。
     *
     * <p>
     * 给扫 ±r 一格一格查的调用点用，即装饰的门与结构引用相的存在性判定：那里一格一次
     * {@code latestChunk}，290 格就是 290 次反射读。主线程独占，游标不可跨线程。
     */
    public static final class ChunkCursor {

        private final Long2ObjectLinkedOpenHashMap<ChunkHolder> visible;

        private ChunkCursor(Long2ObjectLinkedOpenHashMap<ChunkHolder> visible) {
            this.visible = visible;
        }

        /** 该格当前的 LevelChunk；取不到或还不是 LevelChunk 时返回 null。 */
        public LevelChunk at(int cx, int cz) {
            ChunkHolder holder = this.visible.get(ChunkPos.pack(cx, cz));
            return holder != null && holder.getLatestChunk() instanceof LevelChunk lc ? lc : null;
        }

        /** 该格当前的票位级；取不到 holder 时返回 -1。主线程调用。 */
        public int ticketLevelAt(int cx, int cz) {
            ChunkHolder holder = this.visible.get(ChunkPos.pack(cx, cz));
            return holder == null ? -1 : holder.getTicketLevel();
        }
    }

    /** 造一个游标，只做一次反射读。主线程调用。 */
    @SuppressWarnings("unchecked")
    public static ChunkCursor cursor(ServerLevel level) {
        try {
            Object visible = F_VISIBLE_CHUNKS.get(level.getChunkSource().chunkMap);
            return new ChunkCursor((Long2ObjectLinkedOpenHashMap<ChunkHolder>) visible);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 非阻塞取该位置的 holder；没有时返回 null。主线程调用。
     *
     * <p>只给补 FULL 用：那条路要 holder 而不只是 chunk，而 visibleChunkMap 是裸 map。
     */
    @SuppressWarnings("unchecked")
    public static ChunkHolder latestHolder(ServerLevel level, int cx, int cz) {
        try {
            Object visible = F_VISIBLE_CHUNKS.get(level.getChunkSource().chunkMap);
            return ((Long2ObjectLinkedOpenHashMap<ChunkHolder>) visible).get(ChunkPos.pack(cx, cz));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
