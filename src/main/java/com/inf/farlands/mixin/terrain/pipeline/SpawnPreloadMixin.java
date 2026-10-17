package com.inf.farlands.mixin.terrain.pipeline;

import java.util.HashSet;
import java.util.Set;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
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

    /** 出生点搜索的块级偏移最多跨一个 chunk，PLAYER_SPAWN 的半径是 3，取 4 覆盖两者。 */
    @Unique
    private static final int FARLANDS_PRELOAD_RADIUS = 4;

    /**
     * 驱动生成的半径。取判据半径 + 1，这是下界。
     *
     * <p>
     * 门的判据是「中心所在 chunk 的 3×3 九格都过 CARVERS」。判据圈边界那格在偏移
     * {@code FARLANDS_PRELOAD_RADIUS}，它的九格含偏移
     * {@code FARLANDS_PRELOAD_RADIUS + 1}，所以那一圈
     * 必须被驱动，即驱动半径至少比判据半径大 1。取成与判据相等时，9×9 边界那一列的门恒不过，那些段
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
        // 半径取判据半径：出生搜索的偏移在这一圈内。
        Scheduler.Face face = new Scheduler.Face(level.dimension(), spawnChunk.x(), spawnChunk.z(),
                FARLANDS_PRELOAD_RADIUS, minSy, maxSy);
        Scheduler.register(face);
        level.getChunkSource().addTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, spawnChunk,
                FARLANDS_PRELOAD_TICKET_RADIUS);
        SpawnPreload.registerCreation(level.dimension(), face, spawnChunk, FARLANDS_PRELOAD_TICKET_RADIUS);
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
