package com.inf.farlands.mixin.terrain.pipeline;

import java.util.HashSet;
import java.util.Set;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.inf.farlands.terrain.debug.LoadStageSnapshot;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.terrain.pipeline.SpawnPreload;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 出生区预加载：主循环开始之前，把出生点附近的段推到 LIGHTED。
 *
 * <p>
 * 它是入场前的硬前提。PlayerSpawnFinder 在玩家进名单之前就用两参 getChunk 取整块并读高度图，
 * 那条路是阻塞 FULL，而此刻没有玩家、没有窗口、就绪按设计不可达：只有这里先把出生区做成就绪，那次读
 * 才不会永久停在 managedBlock 上。所以循环没有墙钟上限，退出条件只剩范围内全部点亮；不收敛时只报
 * 一次停滞明细，不早退，因为早退的结局同样是把玩家关在加载界面外，只是挂得更晚、更没有诊断。
 *
 * <p>
 * 预加载期间窗口并集为空，而生成与读回都按窗口驱动，所以这里走显式段范围：调度器的准入门认面，
 * 本类把范围交给 {@code Scheduler.submit}；{@code SectionLifecycle.preloadRange}
 * 用同一范围发起
 * 读回并置窗口闩，否则第七行永远不成立，入场后连方块都放不下去。
 *
 * <p>
 * 范围内每段先种 biome 再入队：窗口并集为空时 BiomeFiller.fillChunkBiomes 什么都不会种，而
 * fillSectionBiomes 有 stage 小于 BIOMES 的门，等生成把 stage 推上去之后再补就跳过了，出生区会按
 * 默认群系成型。
 *
 * <p>
 * 驱动：prepareLevels 在主循环之前，ticket 到 chunk 的调度要手动 cache.tick，短路链的
 * thenApplyAsync 要 waitUntilNextTick 的 runAllTasks。每轮让出一次 tick，防忙循环饿死同 JVM 的渲染。
 *
 * <p>
 * 票留到该维度第一个玩家入场才由 SpawnPreloadJoinMixin 移除。
 */
@Mixin(MinecraftServer.class)
public abstract class SpawnPreloadMixin {

    /**
     * 判据圈半径。它由入场等待决定，不由出生点搜索决定。
     *
     * <p>入场等待取 {@code PrepareSpawnTask} 给的半径 3，要求那 7 乘 7 的实体 chunk 全部就绪；而每
     * 一格要就绪，要求它自己的 5 乘 5 邻域到 {@code FULL}，两者并起来是 11 乘 11，即半径 5。取 4 时
     * 判据圈只有 9 乘 9，外圈 24 格的 5 乘 5 伸出圈外，那些格的确认永不完成，入场随即挂在实体等待上。
     */
    @Unique
    private static final int FARLANDS_PRELOAD_RADIUS = 5;

    /**
     * 驱动生成的半径。取判据半径 + 1，这是下界。
     *
     * <p>
     * 门的判据是「中心所在 chunk 的 3×3 九格都过 CARVERS」。判据圈边界那格在偏移
     * {@code FARLANDS_PRELOAD_RADIUS}，它的九格含偏移
     * {@code FARLANDS_PRELOAD_RADIUS + 1}，所以那一圈
     * 必须被驱动，即驱动半径至少比判据半径大 1。取成与判据相等时，判据圈边界那一列的门恒不过，那些段
     * 到不了 LIGHTED，退出条件永不满足，而它们的壳在场、邻域也齐，症状看着像卡在别处。
     *
     * <p>
     * 被驱动的最外一圈自己的门会失败，它的九格含驱动圈之外；但那不影响判据圈：那一圈只需要到
     * CARVERS 供判据圈的门使用，不需要到 LIGHTED。
     */
    @Unique
    private static final int FARLANDS_PRELOAD_DRIVE_RADIUS = FARLANDS_PRELOAD_RADIUS + 1;

    /**
     * 铺票的半径，只用来让壳在场，不驱动生成。取驱动半径 + {@code STRUCTURE_READ_RADIUS}，这是下界。
     *
     * <p>
     * 被驱动的 chunk 里最远的一个在驱动半径，而它的结构引用相要读 ±8，所以它的 ±8 邻域必须都在
     * {@code visibleChunkMap} 里；落到铺票半径即二者之和。结构相本身不再按需补钉，引用相由调度器的
     * 准入调，准入门判 {@code referencesBuildable}，而这一圈票就是它成立的前提。
     */
    @Unique
    private static final int FARLANDS_PRELOAD_TICKET_RADIUS = FARLANDS_PRELOAD_DRIVE_RADIUS
            + StructureDriver.STRUCTURE_READ_RADIUS;

    /** 竖直半高。 */
    @Unique
    private static final int FARLANDS_PRELOAD_HALF_Y = 3;

    /**
     * 连续多少毫秒 ready 不增才算停滞。取 30 秒是刻意的宽：每轮固定让出 10 毫秒，而世界生成的一个
     * 批次间隔本来就可能上秒，按轮数计会把正常批次判成停滞，刷出一堆假警报。
     */
    @Unique
    private static final long FARLANDS_PRELOAD_STALL_MILLIS = 30_000L;

    /** 停滞明细最多列几个 chunk。 */
    @Unique
    private static final int FARLANDS_PRELOAD_STALL_LIST = 8;

    /**
     * 泵的停止判据半径：出生面每格的结构读域都已在场，即面半径加结构读半径，29×29 共 841 格。
     *
     * <p>壳要由票传播造出来，而票传播只在 {@code ServerChunkCache.tick} 里跑；{@code setInitialSpawn}
     * 的阻塞取数又发生在 tick 循环之前。不在这里把票推一轮，结构引用相的 ±8 门在整个建世界期恒假，
     * 出生块永远等不到 FULL。
     */
    @Unique
    private static final int FARLANDS_CREATION_SHELL_RADIUS = FARLANDS_PRELOAD_DRIVE_RADIUS
            + StructureDriver.STRUCTURE_READ_RADIUS;

    /**
     * 建世界期泵的墙钟上限。超时即放行，退回原来的阻塞等待，不把建世界期拖成无界。
     *
     * <p>取 45 秒而不是按轮数：泵的职责已经从「造壳」扩到「当管道清空前的钟」，退出点由两条判据决定，
     * 即壳齐与判据圈全亮，时间只是兜底。实测装饰吞吐约 4 个/秒，判据圈 121 个需要约 30 秒才在数学上够，
     * 15 秒的上限本身就会截断收敛。
     */
    @Unique
    private static final long FARLANDS_CREATION_PUMP_MILLIS = 45000L;

    /** 泵每轮让出的毫秒数，只在壳还没齐时让。 */
    @Unique
    private static final long FARLANDS_CREATION_PUMP_YIELD_MILLIS = 1L;

    /** 数壳的轮数间隔。841 格一次数太密。 */
    @Unique
    private static final int FARLANDS_CREATION_SHELL_SAMPLE_ROUNDS = 8;

    /**
     * 兜底重扫的轮数间隔。
     *
     * <p>退避重投有次数上限，约 12.6 秒后在等邻居的候选会被丢掉，而邻居落档之后不一定有人再提交
     * 它。每这么多轮把判据圈整圈重投一次，等于给这些等待续期。实测一轮约 0.28 毫秒，4096 轮约 1.1 秒。
     */
    @Unique
    private static final int FARLANDS_CREATION_RESCAN_ROUNDS = 4096;

    @Shadow
    private long nextTickTimeNanos;

    /**
     * 与 {@link #nextTickTimeNanos} 一起构成 haveTime() 的判据：mayHaveDelayedTasks
     * 为真时，haveTime()
     * 读的是本字段。它只在服务器 tick 循环里被刷新，而 prepareLevels 跑在 tick 循环之前，所以本类
     * 每轮同步推一次，见循环末尾。
     */
    @Shadow
    private long delayedTasksMaxNextTickTimeNanos;

    @Shadow
    @Final
    private static long PREPARE_LEVELS_DEFAULT_DELAY_NANOS;

    @Shadow
    protected abstract void waitUntilNextTick();

    /**
     * 世界创建期的驱动面：`setInitialSpawn` 里的阻塞 FULL 取数发生在玩家进名单之前，此刻没有玩家
     * 快照也没有任何力面，准入的面判据恒假，那个取数永远等不到。这里在它之前把出生区登记成一个面，
     * 并铺一张足以覆盖驱动面 ±8 的大半径票；首个玩家入场时由 {@link SpawnPreload#release} 一并撤。
     *
     * <p>
     * 中心与 vanilla 同源：它自己用 {@code findSpawnPosition} 算出生 chunk，这里重复算一次，
     * 那是种子的纯函数。段带取出生高度上下 {@code FARLANDS_PRELOAD_HALF_Y}，与预加载循环同宽。
     */
    @Inject(method = "setInitialSpawn", at = @At("HEAD"))
    private static void farlands$creationFace(ServerLevel level, ServerLevelData levelData, boolean spawnBonusChest,
            boolean isDebug, LevelLoadListener levelLoadListener, CallbackInfo ci) {
        ChunkPos spawnChunk = ChunkPos.containing(
                level.getChunkSource().randomState().sampler().findSpawnPosition());
        int spawnSy = level.getChunkSource().getGenerator().getSpawnHeight(level) >> 4;
        int minSy = Math.max(spawnSy - FARLANDS_PRELOAD_HALF_Y, WorldBounds.MIN_SECTION);
        int maxSy = Math.min(spawnSy + FARLANDS_PRELOAD_HALF_Y, WorldBounds.MAX_SECTION);
        // 半径取驱动半径，不是判据半径。判据圈边界那格的九格含偏移判据半径加一，取成与判据相等时
        // 那一圈的邻居在面外、没有段条目，九格门恒假，那 32 个 chunk 永远到不了 LIGHTED。
        Scheduler.Face face = new Scheduler.Face(level.dimension(), spawnChunk.x(), spawnChunk.z(),
                FARLANDS_PRELOAD_DRIVE_RADIUS, minSy, maxSy);
        Scheduler.register(face);
        level.getChunkSource().addTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, spawnChunk,
                FARLANDS_PRELOAD_TICKET_RADIUS);
        SpawnPreload.registerCreation(level.dimension(), face, spawnChunk, FARLANDS_PRELOAD_TICKET_RADIUS);
        farlands$pumpCreation(level, spawnChunk, minSy, maxSy);
    }

    /**
     * 建世界期泵：票传播 -> 主线程回调 -> 调度器派发，直到壳齐且判据圈全亮，或到时间上限。
     *
     * <p>
     * 三步缺一不可。{@code cache.tick} 把票换成 holder；壳的构造在
     * {@code GenerationChunkHolderMixin} 的 thenAccept 里投回主线程，所以要抽一次主线程执行器；
     * 派发是 tick 驱动的，而这里没有 tick，所以要显式调一次。
     *
     * <p>
     * 两条退出判据都要：只判「壳齐」会在推进刚开始时退出，只判「判据圈全亮」会在壳还没造出来时
     * 空转到上限。泵是这一段唯一的钟，判据必须覆盖它的全部职责。
     *
     * <p>
     * 时间上限是硬约束：走到上限就放行，{@code setInitialSpawn} 回到原来的阻塞等待。预加载循环在
     * {@code prepareLevels} 里还会再推一次，那时 tick 循环已就绪，派发不再依赖本方法。
     */
    @Unique
    private static void farlands$pumpCreation(ServerLevel level, ChunkPos center, int minSy, int maxSy) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        ServerChunkCache cache = level.getChunkSource();
        int total = (FARLANDS_CREATION_SHELL_RADIUS * 2 + 1) * (FARLANDS_CREATION_SHELL_RADIUS * 2 + 1);
        long startedAt = System.currentTimeMillis();
        int rounds = 0;
        int shells = farlands$countShells(level, center);
        try {
            while (System.currentTimeMillis() - startedAt < FARLANDS_CREATION_PUMP_MILLIS) {
                // 两条同时成立才退出：壳齐、判据圈全亮。只判壳齐会在推进刚开始时就退出，而泵是这一段
                // 唯一的钟，退早了管道就停在半路没人再派发。
                //
                // 不判「候选清空」：驱动面比判据圈大一圈，外圈那些格永远过不了装饰门，它们只能在退避
                // 次数到顶时被丢掉，约 12.6 秒。判据圈已亮时它们与入场无关，再等只是白等。
                if (shells >= total && farlands$bandLit(level, center, minSy, maxSy)) {
                    break;
                }
                cache.tick(() -> false, false);
                SectionIO.drainMainThreadTasks(server);
                Scheduler.tick();
                // 加载页网格读数：快照自己按墙钟限流，这里每轮调一次是廉价的。
                LoadStageSnapshot.publish(level, center, minSy, maxSy, FARLANDS_PRELOAD_TICKET_RADIUS);
                rounds++;
                // 兜底重扫：等邻居的候选会在退避上限处被丢掉，而邻居落档之后不一定有人再提交它。
                // 每 N 轮把判据圈整圈重投一次，等于给这些等待续期；圈外不碰，所以不会把几何死区
                // 那几个 chunk 变成空转。
                if (rounds % FARLANDS_CREATION_RESCAN_ROUNDS == 0) {
                    farlands$rescanCriterion(level, center);
                }
                // 数壳与让出都按轮数间隔：841 格一次数不便宜，而每轮都让出会白等。
                if (rounds % FARLANDS_CREATION_SHELL_SAMPLE_ROUNDS == 0) {
                    shells = farlands$countShells(level, center);
                    Thread.sleep(FARLANDS_CREATION_PUMP_YIELD_MILLIS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 判据圈逐格重投。主线程调用，圈半径取判据半径。 */
    @Unique
    private static void farlands$rescanCriterion(ServerLevel level, ChunkPos center) {
        int radius = FARLANDS_PRELOAD_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = ChunkReadiness.chunkAt(level, new ChunkPos(center.x() + dx, center.z() + dz));
                if (chunk != null) {
                    Scheduler.submit(chunk);
                }
            }
        }
    }

    /** 出生面的结构读域内已有壳的格数。主线程调用。 */
    @Unique
    private static int farlands$countShells(ServerLevel level, ChunkPos center) {
        int present = 0;
        for (int dx = -FARLANDS_CREATION_SHELL_RADIUS; dx <= FARLANDS_CREATION_SHELL_RADIUS; dx++) {
            for (int dz = -FARLANDS_CREATION_SHELL_RADIUS; dz <= FARLANDS_CREATION_SHELL_RADIUS; dz++) {
                if (ChunkReadiness.chunkAt(level, new ChunkPos(center.x() + dx, center.z() + dz)) != null) {
                    present++;
                }
            }
        }
        return present;
    }

    /** 判据圈是否全部点亮。圈内任一格壳不在场，或段带里有一段未到 LIGHTED 即为假。 */
    @Unique
    private static boolean farlands$bandLit(ServerLevel level, ChunkPos center, int minSy, int maxSy) {
        int radius = FARLANDS_PRELOAD_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = ChunkReadiness.chunkAt(level, new ChunkPos(center.x() + dx, center.z() + dz));
                if (chunk == null) {
                    return false;
                }
                for (int sy = minSy; sy <= maxSy; sy++) {
                    if (WorldBounds.inSection(sy) && !SectionStage.isOrAfter(chunk, sy, SectionStage.LIGHTED)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Inject(method = "prepareLevels", at = @At("TAIL"))
    private void farlands$preloadSpawnArea(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 中心与 PlayerSpawnFinder 的建议点同源：它取的就是存量 respawn data 的 pos，
        // 维度取该 data 的维度，缺失时退回主世界。
        LevelData.RespawnData respawnData = server.getWorldData().overworldData().getRespawnData();
        ServerLevel level = server.getLevel(respawnData.dimension());
        if (level == null) {
            level = server.overworld();
        }
        BlockPos spawnPos = respawnData.pos();
        int spawnCx = SectionPos.blockToSectionCoord(spawnPos.getX());
        int spawnCz = SectionPos.blockToSectionCoord(spawnPos.getZ());
        int spawnSy = SectionPos.blockToSectionCoord(spawnPos.getY());
        int minSy = Math.max(spawnSy - FARLANDS_PRELOAD_HALF_Y, WorldBounds.MIN_SECTION);
        int maxSy = Math.min(spawnSy + FARLANDS_PRELOAD_HALF_Y, WorldBounds.MAX_SECTION);
        int syCount = 0;
        for (int sy = minSy; sy <= maxSy; sy++) {
            if (WorldBounds.inSection(sy)) {
                syCount++;
            }
        }
        int side = FARLANDS_PRELOAD_RADIUS * 2 + 1;
        int total = syCount * side * side;
        if (total <= 0) {
            return;
        }

        ServerChunkCache cache = level.getChunkSource();
        Set<Long> issued = new HashSet<>();
        for (int dx = -FARLANDS_PRELOAD_TICKET_RADIUS; dx <= FARLANDS_PRELOAD_TICKET_RADIUS; dx++) {
            for (int dz = -FARLANDS_PRELOAD_TICKET_RADIUS; dz <= FARLANDS_PRELOAD_TICKET_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(spawnCx + dx, spawnCz + dz);
                cache.addTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, pos, 0);
                SpawnPreload.register(level.dimension(), pos);
            }
        }

        int ready = farlands$countReady(level, spawnCx, spawnCz, minSy, maxSy);
        long startedAt = System.currentTimeMillis();
        long lastProgressAt = startedAt;
        int lastReady = ready;
        // 登记力面：此刻玩家还没进名单，准入的面查询只有靠它才为真。半径与驱动的方阵同宽，段范围与
        // 范围读回同宽。
        Scheduler.Face face = new Scheduler.Face(level.dimension(), spawnCx, spawnCz,
                FARLANDS_PRELOAD_DRIVE_RADIUS, minSy, maxSy);
        Scheduler.register(face);
        try {
            while (ready < total) {
                cache.tick(() -> false, false);
                this.waitUntilNextTick();
                for (int dx = -FARLANDS_PRELOAD_DRIVE_RADIUS; dx <= FARLANDS_PRELOAD_DRIVE_RADIUS; dx++) {
                    for (int dz = -FARLANDS_PRELOAD_DRIVE_RADIUS; dz <= FARLANDS_PRELOAD_DRIVE_RADIUS; dz++) {
                        ChunkPos pos = new ChunkPos(spawnCx + dx, spawnCz + dz);
                        LevelChunk chunk = ChunkReadiness.chunkAt(level, pos);
                        if (chunk == null) {
                            continue;
                        }
                        if (issued.add(pos.pack())) {
                            SectionLifecycle.preloadRange(chunk, minSy, maxSy);
                            for (int sy = minSy; sy <= maxSy; sy++) {
                                if (WorldBounds.inSection(sy)) {
                                    BiomeFiller.fillSectionBiomes(level, chunk, sy);
                                }
                            }
                        }
                        if (!SectionIO.isReadingAny(chunk)) {
                            int[] range = new int[maxSy - minSy + 1];
                            for (int i = 0; i < range.length; i++) {
                                range[i] = minSy + i;
                            }
                            Scheduler.submit(chunk, range);
                        }
                    }
                }
                // 调度器在这里驱动：它由 tick 驱动，而预加载跑在 tick 之前。不补这一步，判据圈的段会永远停在
                // CARVERS，而本循环的退出条件是范围内全部点亮，那就永远等不到。
                Scheduler.tick();
                // 抽一次主线程执行器。装饰的收尾把升段与撤认领投在这里，fsa 的编码提交也在这里，
                // 而 prepareLevels 期间的 waitUntilNextTick 可能不再抽各 level 的 chunk 执行器，
                // 与 GenQueue.awaitIdle 的同形处理一致。不抽它，收尾永远落不下来，认领被一直持有。
                SectionIO.drainMainThreadTasks(server);
                // 加载页网格读数：本循环本来就按 tick 节奏走，快照自己再按墙钟限流。
                LoadStageSnapshot.publish(level, new ChunkPos(spawnCx, spawnCz), minSy, maxSy,
                        FARLANDS_PRELOAD_TICKET_RADIUS);
                ready = farlands$countReady(level, spawnCx, spawnCz, minSy, maxSy);
                long now = System.currentTimeMillis();
                if (ready != lastReady) {
                    lastReady = ready;
                    lastProgressAt = now;
                } else if (now - lastProgressAt >= FARLANDS_PRELOAD_STALL_MILLIS) {
                    // 每个停滞窗口报一次：报完把基准推到现在，持续停滞才会每 30 秒再报一条。
                    InfsFarlands.LOGGER.error("farlands: spawn preload stuck at {}/{} for {} ms, detail: {}",
                            ready, total, now - lastProgressAt,
                            farlands$stallDetail(level, spawnCx, spawnCz, minSy, maxSy, syCount));
                    lastProgressAt = now;
                }
                this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;
                // 与上面同一判据的另一半：mayHaveDelayedTasks 在 prepareLevels 期间会被 pollTask 置真，
                // 之后 haveTime() 只读 delayedTasksMaxNextTickTimeNanos。不同步推它，haveTime() 恒假，
                // waitUntilNextTick 既不抽 chunk 执行器，也不让出 tick，本循环退化成热自旋。
                this.delayedTasksMaxNextTickTimeNanos = this.nextTickTimeNanos;
                this.waitUntilNextTick();
            }
        } finally {
            Scheduler.unregister(face);
        }

        // 存在流程派发的 loadChunkSections 可能晚于范围读回，落完队列再放行 FULL。
        cache.tick(() -> false, false);
        this.waitUntilNextTick();
        InfsFarlands.LOGGER.info("farlands: spawn preload {}/{} in {} ms", ready, total,
                System.currentTimeMillis() - startedAt);
    }

    /** 范围内已点亮的段数。 */
    @Unique
    private int farlands$countReady(ServerLevel level, int centerCx, int centerCz, int minSy, int maxSy) {
        int ready = 0;
        for (int dx = -FARLANDS_PRELOAD_RADIUS; dx <= FARLANDS_PRELOAD_RADIUS; dx++) {
            for (int dz = -FARLANDS_PRELOAD_RADIUS; dz <= FARLANDS_PRELOAD_RADIUS; dz++) {
                LevelChunk chunk = ChunkReadiness.chunkAt(level, new ChunkPos(centerCx + dx, centerCz + dz));
                if (chunk == null) {
                    continue;
                }
                for (int sy = minSy; sy <= maxSy; sy++) {
                    if (WorldBounds.inSection(sy) && SectionStage.isOrAfter(chunk, sy, SectionStage.LIGHTED)) {
                        ready++;
                    }
                }
            }
        }
        return ready;
    }

    /** 停滞明细：还没点亮的 chunk，最多列若干个，带在途标志与未点亮段数。 */
    @Unique
    private String farlands$stallDetail(ServerLevel level, int centerCx, int centerCz, int minSy, int maxSy,
            int perChunk) {
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (int dx = -FARLANDS_PRELOAD_RADIUS; dx <= FARLANDS_PRELOAD_RADIUS
                && listed < FARLANDS_PRELOAD_STALL_LIST; dx++) {
            for (int dz = -FARLANDS_PRELOAD_RADIUS; dz <= FARLANDS_PRELOAD_RADIUS
                    && listed < FARLANDS_PRELOAD_STALL_LIST; dz++) {
                ChunkPos pos = new ChunkPos(centerCx + dx, centerCz + dz);
                LevelChunk chunk = ChunkReadiness.chunkAt(level, pos);
                if (chunk == null) {
                    sb.append(pos).append("[no-shell] ");
                    listed++;
                    continue;
                }
                int done = 0;
                for (int sy = minSy; sy <= maxSy; sy++) {
                    if (WorldBounds.inSection(sy) && SectionStage.isOrAfter(chunk, sy, SectionStage.LIGHTED)) {
                        done++;
                    }
                }
                if (done >= perChunk) {
                    continue;
                }
                int[] below = { 0 };
                SectionStage.forEachStage(chunk, (sy, stage) -> {
                    if (SectionStage.isBelowLighted(stage)) {
                        below[0]++;
                    }
                });
                sb.append(pos).append("[lighted=").append(done).append('/').append(perChunk)
                        .append(",busy=").append(GenQueue.isChunkBusy(chunk))
                        .append(",biome=").append(GenQueue.isBiomeFilling(chunk))
                        .append(",reading=").append(SectionIO.isReadingAny(chunk))
                        .append(",pending=").append(!com.inf.farlands.serialize.ChunkCounters
                                .isWindowReadDone(chunk))
                        .append(",belowLighted=").append(below[0])
                        .append("] ");
                listed++;
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
