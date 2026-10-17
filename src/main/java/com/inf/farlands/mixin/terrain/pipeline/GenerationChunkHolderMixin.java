package com.inf.farlands.mixin.terrain.pipeline;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.structure.StructureDriver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 地形管线：拦截 vanilla chunk 生成调度，短路到 FULL 空壳。
 *
 * 所有 chunk 生成入口，即 getChunk 任意状态请求与 ChunkHolder 的 prepare 链，都汇聚到
 * scheduleChunkGenerationTask。拦截后不创建 ChunkGenerationTask，生成金字塔一行不跑，改为
 * 读盘或建空壳 ProtoChunk，构造 LevelChunk，逐状态 completeFuture，于是全部请求立即拿到
 * LevelChunk。per-section 生成由独立的 terrain 管线异步驱动。
 *
 * 26.1.2 的 vanilla 方法体内有 task 与 applyStep 机制，本覆写整体替换它且从不设置 task，
 * 因此 ChunkGenerationTask 永不创建，金字塔不会被旁路驱动。
 *
 * 线程：拦截在主线程；scheduleChunkLoad 内部后台读盘加主线程建 ProtoChunk；thenAccept 跟随主线程。
 */
@Mixin(GenerationChunkHolder.class)
public abstract class GenerationChunkHolderMixin {

    // startedWork 必须置 FULL，否则 getLatestChunk 返回 null，saveChunkIfNeeded 永不保存。
    // final 字段用 @Shadow 非 final 声明，mixin 生成 putfield 直写，绕开反射写 final 的限制。
    @Shadow
    private AtomicReference<ChunkStatus> startedWork;

    @Shadow
    public abstract ChunkPos getPos();

    @Shadow
    public abstract FullChunkStatus getFullStatus();

    private static final Method M_SCHEDULE_CHUNK_LOAD;
    private static final Field F_LEVEL;
    private static final Method M_GET_OR_CREATE_FUTURE;
    private static final Method M_COMPLETE_FUTURE;
    private static final Method M_IS_STATUS_DISALLOWED;

    static {
        try {
            M_SCHEDULE_CHUNK_LOAD = ChunkMap.class.getDeclaredMethod("scheduleChunkLoad", ChunkPos.class);
            M_SCHEDULE_CHUNK_LOAD.setAccessible(true);
            F_LEVEL = ChunkMap.class.getDeclaredField("level");
            F_LEVEL.setAccessible(true);
            M_GET_OR_CREATE_FUTURE = GenerationChunkHolder.class.getDeclaredMethod("getOrCreateFuture",
                    ChunkStatus.class);
            M_GET_OR_CREATE_FUTURE.setAccessible(true);
            M_COMPLETE_FUTURE = GenerationChunkHolder.class.getDeclaredMethod("completeFuture", ChunkStatus.class,
                    ChunkAccess.class);
            M_COMPLETE_FUTURE.setAccessible(true);
            M_IS_STATUS_DISALLOWED = GenerationChunkHolder.class.getDeclaredMethod("isStatusDisallowed",
                    ChunkStatus.class);
            M_IS_STATUS_DISALLOWED.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private AtomicBoolean farlandsExistenceStarted;

    /**
     * 延迟创建标志，不用字段初始化器。本类没有显式构造器，@Unique 实例字段的初始化器可能被静默
     * 丢弃，读到时会是 null。
     */
    @Unique
    private AtomicBoolean farlandsExistence() {
        AtomicBoolean b = this.farlandsExistenceStarted;
        if (b == null) {
            b = new AtomicBoolean();
            this.farlandsExistenceStarted = b;
        }
        return b;
    }

    /** 每 holder 短路只跑一次。completeFuture 对已成功完成的 future 再 complete 会抛。 */
    @Overwrite
    public CompletableFuture<ChunkResult<ChunkAccess>> scheduleChunkGenerationTask(ChunkStatus targetStatus,
            ChunkMap chunkMap) {
        if (farlandsIsStatusDisallowed(targetStatus)) {
            return GenerationChunkHolder.UNLOADED_CHUNK_FUTURE;
        }
        CompletableFuture<ChunkResult<ChunkAccess>> future = farlandsGetOrCreateFuture(targetStatus);
        if (future.isDone()) {
            return future;
        }
        if (farlandsExistence().compareAndSet(false, true)) {
            ChunkPos pos = this.getPos();
            try {
                CompletableFuture<ChunkAccess> load = farlandsScheduleChunkLoad(chunkMap, pos);
                load.thenAccept(proto -> this.farlandsCompleteEmpty(chunkMap, proto));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return future;
    }

    /** 主线程：ProtoChunk 转 LevelChunk 容器构造，生命周期，逐状态 completeFuture。 */
    @Unique
    private void farlandsCompleteEmpty(ChunkMap chunkMap, ChunkAccess proto) {
        try {
            ServerLevel level = (ServerLevel) F_LEVEL.get(chunkMap);
            boolean isNew = !(proto instanceof ImposterProtoChunk);
            LevelChunk levelchunk;
            if (!isNew) {
                levelchunk = ((ImposterProtoChunk) proto).getWrapped();
            } else {
                levelchunk = new LevelChunk(level, (ProtoChunk) proto, p -> {
                });
            }
            levelchunk.setFullStatus(this::getFullStatus);
            levelchunk.runPostLoad();
            levelchunk.setLoaded(true);
            levelchunk.registerAllBlockEntitiesAfterLevelLoad();
            levelchunk.registerTickContainerInLevel(level);

            this.startedWork.set(ChunkStatus.FULL);

            // FULL 不在这里完成：它的门现在是数据就绪，由 ChunkReadiness 在就绪时补。只置到 SPAWN，
            // getLatestChunk 在当前状态 future 未完成时回退到父状态，因此仍返回这个 LevelChunk，
            // 存盘与 fsa 的遍历不受影响。
            for (ChunkStatus status : ChunkStatus.getStatusList()) {
                if (status == ChunkStatus.FULL) {
                    continue;
                }
                farlandsCompleteFuture(status, levelchunk);
            }
            ChunkReadiness.watch((GenerationChunkHolder) (Object) this, levelchunk);
            // 这一格的壳出现了：等它的装饰项要重判门，等它的结构挂起项要重判 ±8。
            DecorationFiller.cellChanged(levelchunk.getPos().pack());
            StructureDriver.cellChanged();

            // 结构相早于 Beardifier，Beardifier 早于 fill。这是铁的顺序，但它只约束 fill，不约束就绪。
            // 所以这里只挂起引用相与 Beardifier 那一小段，尾部照跑，尾部是 biome、读回、入队与 drive。
            // setInitialSpawn 那条阻塞 FULL 早于预加载循环，加载期没有第二次 drive 的机会。
            //
            // 顺序由 GenTask.execute 的门保证，而那道门查的是不变量本身，即没有 Beardifier 就不填，
            // 不是「有没有登记为等结构相」这个代理：登记的落点晚于本 chunk 对 latestChunk 可见的那一刻。
            //
            // 结构生成被关掉的世界走捷径：那时 createStructures 什么都不会建、引用表保持为空，而
            // forStructuresInChunk 对空起点表返回的就是 Beardifier.EMPTY。所以跳过起点相、门与 pin
            // 和照常跑完，交出来的是同一个对象。照调 forStructuresInChunk 而不是直接塞 EMPTY，是为了
            // 不让这里成为唯一算得出特例的地方。
            //
            // 可玩界之外的 chunk 走同一支：它们没有可玩方块，起点相也不该跑，取到的是同一个 EMPTY。
            if (!StructureDriver.structuresEnabled(level) || !StructureDriver.inStructureRange(levelchunk)) {
                ((ChunkBeardifier) levelchunk).setBeardifier(
                        Beardifier.forStructuresInChunk(level.structureManager(), levelchunk.getPos()));
            } else {
                StructureDriver.ensureStarts(level, levelchunk);
                if (StructureDriver.referencesBuildable(level, levelchunk)) {
                    farlandsStructureReady(level, levelchunk);
                } else {
                    StructureDriver.await(level, levelchunk,
                            () -> farlandsStructureReady(level, levelchunk));
                }
            }
            farlandsGenerateTail(level, levelchunk);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("farlands: existence flow failed chunk={}", proto.getPos(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 结构相：引用相，然后 Beardifier，最后补一次生成入队。
     *
     * <p>与挂起标记的摘除同线程、同一次调用内相邻完成，{@code StructureDriver.tick} 先摘再跑，
     * 所以不存在「门已开、Beardifier 未设」的窗口。
     *
     * <p>末尾必须补一次入队：存在流程尾部那次 {@code enqueueChunk} 会被 GenTask 的门早退掉，而挂起
     * 期的 chunk 在扫描里也重排不上，预加载期没有窗口段，hasUnprocessed 迭代不到任何段。
     */
    @Unique
    private void farlandsStructureReady(ServerLevel level, LevelChunk levelchunk) {
        // 引用相：读中心 ±8 的起点表，写中心自己的引用表。主线程调，且必须先于 Beardifier。
        StructureDriver.buildReferences(level, levelchunk);

        // 结构性地形适配数据：Beardifier.forStructuresInChunk 读中心的引用表，再按引用取各 chunk 的
        // 起点，所以必须在引用相之后。它经 StructureManager 走 ServerChunkCache 的取 chunk，非主线程
        // 上那条路把活踢回主线程并阻塞等待，所以必须在主线程算，此处就是主线程。
        ((ChunkBeardifier) levelchunk).setBeardifier(
                Beardifier.forStructuresInChunk(level.structureManager(), levelchunk.getPos()));

        // 补一次生成入队。三个入口只有 preload 能用：enqueueChunk 的首句是 isNearPlayer 过滤，
        // 预加载期没有玩家，会被直接挡回；enqueue(chunk, sy) 走窗口并集，预加载期窗口为空，扫不出
        // 任何段；preload(chunk, min, max) 走显式段范围，正是走查那一路用的入口。
        // 范围取该 chunk 已物化过的段，走查的 biome 阶段已经把它们建出来了。
        int[] range = { Integer.MAX_VALUE, Integer.MIN_VALUE };
        SectionStage.forEachStage(levelchunk, (sy, stage) -> {
            if (sy < range[0]) {
                range[0] = sy;
            }
            if (sy > range[1]) {
                range[1] = sy;
            }
        });
        if (range[0] <= range[1]) {
            GenQueue.preload(levelchunk, range[0], range[1]);
        } else {
            // 空范围：这一支今天发不出任何入队。被门挡下的 chunk 从没进过 collectSegments，它的
            // stage 表就是空的，于是「门开了」这件事传不出去，只能等扫描那一轮。
        }
    }

    /**
     * 结构相之后照跑的那一支：biome、fsa 读回、入队。
     *
     * <p>它不依赖 {@code Beardifier}，所以结构相被延后时也照跑，加载期的 FULL 就靠它末尾那次
     * {@code ChunkReadiness.completeFull()} 补上，而 {@code setInitialSpawn} 的阻塞读早于预加载循环。
     */
    @Unique
    private void farlandsGenerateTail(ServerLevel level, LevelChunk levelchunk) {
        StageMetrics.chunkLoaded(levelchunk.getPos().pack());
        // biome 阶段独立：后台按窗口并集填 biome 并升 BIOMES，完成后回主线程做 fsa 读回，
        // 读回完成再入生成队列。读回与入队必须回主线程，thenAccept 在后台线程执行。
        //
        // biome 阶段不在生成与光照两张标志内，而它同样会换掉 section 的 biomes 容器，发送侧
        // 据此另查 isBiomeFilling。begin 必须在提交之前由本线程落下，end 必须在填充的最后一笔
        // 写之后：填充抛异常或提交本身失败都要清，否则该 chunk 永久为忙。
        GenQueue.beginBiomeFill(levelchunk);
        // 视距之外不铺群系：为别的 chunk 的 ±8 被钉住加载的壳也落在这一片，它们不会被驱动，铺了也白做。
        // 它们的段停在 UNPROCESSED，而 collectSegments 的判据是「未过 TERRAIN」，照样会收，真群系由
        // GenTask 的预填补上，下发要求 LIGHTED 所以不受影响。判据在主线程取一次：读回与入队照走，而
        // players 是普通 List，后台线程不该遍历它。
        boolean prefill = GenQueue.isNearPlayer(levelchunk);
        try {
            CompletableFuture.runAsync(
                    () -> {
                        boolean filled = false;
                        try {
                            if (prefill) {
                                BiomeFiller.fillChunkBiomes(level, levelchunk);
                            }
                            filled = true;
                        } finally {
                            if (!filled) {
                                GenQueue.endBiomeFill(levelchunk);
                            }
                        }
                    },
                    Util.backgroundExecutor())
                    .thenAccept(v -> SectionIO.runOnMainThread(() -> {
                        // 群系在途标志与读回在同一次主线程任务里交接。若在后台线程就清掉标志，
                        // 从清掉到 loadChunkSections 落下读回标记之间会出现一个既无在途也无读回的
                        // 缝，ChunkReadiness 会把这个缝判成就绪。
                        GenQueue.endBiomeFill(levelchunk);
                        // 群系标志清掉后，一个已读回且已点亮的 chunk 可能刚好就绪，这里补一次。
                        ChunkReadiness.completeFull(levelchunk);
                        // fsa 读回：先查磁盘窗口内 section，有则读回恢复数据、光照、stage 并补发。
                        // 完成后才 enqueueChunk，collectSegments 的 isOrAfter(TERRAIN) 自动跳过已读回的，
                        // 磁盘没有的 section 正常入生成队列。
                        SectionLifecycle.loadChunkSections(levelchunk, () -> {
                            StageMetrics.chunkLoadDone();
                            GenQueue.enqueueChunk(levelchunk);
                        });
                    }, level));
        } catch (RuntimeException e) {
            GenQueue.endBiomeFill(levelchunk);
            throw e;
        }
    }

    /**
     * 上报口径改按「存在流程跑过」算。
     *
     * <p>模板按「该状态对应的 chunk 是否存在」逐级回退，而 FULL 的 future 在 port 里被数据就绪悬着，
     * 于是这里一路回退成 SPAWN。ChunkLoadCounter 判的是等于 FULL，prepareLevels 与入场准备都会因此
     * 永不收敛，客户端的 chunk 网格也一格都画不出来。只改上报，数据门仍在 future 上。
     */
    @Inject(method = "getLatestStatus", at = @At("RETURN"), cancellable = true)
    private void farlands$reportFull(CallbackInfoReturnable<ChunkStatus> cir) {
        if (this.startedWork.get() == ChunkStatus.FULL) {
            cir.setReturnValue(ChunkStatus.FULL);
        }
    }

    @Unique
    private boolean farlandsIsStatusDisallowed(ChunkStatus status) {
        try {
            return (Boolean) M_IS_STATUS_DISALLOWED.invoke(this, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Unique
    private CompletableFuture<ChunkResult<ChunkAccess>> farlandsGetOrCreateFuture(ChunkStatus status) {
        try {
            return (CompletableFuture<ChunkResult<ChunkAccess>>) M_GET_OR_CREATE_FUTURE.invoke(this, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private void farlandsCompleteFuture(ChunkStatus status, ChunkAccess chunk) {
        try {
            M_COMPLETE_FUTURE.invoke(this, status, chunk);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Unique
    private static CompletableFuture<ChunkAccess> farlandsScheduleChunkLoad(ChunkMap chunkMap, ChunkPos pos) {
        try {
            return (CompletableFuture<ChunkAccess>) M_SCHEDULE_CHUNK_LOAD.invoke(chunkMap, pos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
