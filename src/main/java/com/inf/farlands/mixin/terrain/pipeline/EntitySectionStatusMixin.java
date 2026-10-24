package com.inf.farlands.mixin.terrain.pipeline;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断：实体 section 的 chunk 可见性推进读数。
 *
 * <p>玩家入场要过 {@code ServerLevel.waitForEntities}，它的谓词是「该 chunk 的实体状态为 LOADED」，
 * 而 LOADED 只能由本方法的非 HIDDEN 分支推进到 {@code ensureChunkQueuedForLoad} 之后产生。这条链
 * 一旦断在入口，表现就是服务端停在 managedBlock、两个池子全闲、日志一个字不写。
 *
 * <p>本类只打一行，按 chunk 与状态去重，只在 {@code logScheduler} 打开时输出。定位完成后另开一次
 * 改动删除，不混进正式改动。
 */
@Mixin(PersistentEntitySectionManager.class)
public class EntitySectionStatusMixin {

    /** 已打过的 chunk 与状态组合。 */
    private static final Set<Long> LOGGED = ConcurrentHashMap.newKeySet();

    /** 已逐键打过的加载请求键，防同一键在重试时重复输出。 */
    @Unique
    private static final Set<Long> farlands$REQUESTED = ConcurrentHashMap.newKeySet();

    /** 加载请求的总次数，逐键去重之外的另一个口径，用作探针自身的有效性证据。 */
    @Unique
    private static final AtomicInteger farlands$REQUESTS = new AtomicInteger();

    /** 逐键输出的前多少条。此后改为每 128 个新键打一条汇总。 */
    @Unique
    private static final int farlands$REQUEST_LOG_LIMIT = 256;

    @Inject(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/entity/Visibility;)V", at = @At("HEAD"))
    private void farlands$logChunkStatus(ChunkPos pos, Visibility status, CallbackInfo ci) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        long key = (pos.pack() << 4) | (status.ordinal() & 0xF);
        if (!LOGGED.add(key)) {
            return;
        }
        InfsFarlands.LOGGER.info("FLENT update chunk={},{} status={}", pos.x(), pos.z(), status.name());
    }

    /**
     * 诊断：实体加载请求读数。
     *
     * <p>LOADED 的唯一来源是本方法，它只在 chunk 状态由 FRESH 转入 PENDING 时被调。本读数与
     * FLWAIT 那一面合看，可以分开两种互斥形态：请求从未发出，或请求发了但没落地。总数那一栏是
     * 探针自身的有效性证据，逐键零命中时才分得清「没发生」与「没覆盖」。
     */
    @Inject(method = "requestChunkLoad", at = @At("HEAD"))
    private void farlands$logChunkLoadRequest(long chunkKey, CallbackInfo ci) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        int total = farlands$REQUESTS.incrementAndGet();
        if (!farlands$REQUESTED.add(chunkKey)) {
            return;
        }
        int distinct = farlands$REQUESTED.size();
        if (distinct <= farlands$REQUEST_LOG_LIMIT) {
            ChunkPos pos = ChunkPos.unpack(chunkKey);
            InfsFarlands.LOGGER.info("FLREQ request chunk={},{} total={}", pos.x(), pos.z(), total);
        } else if ((distinct & 127) == 0) {
            InfsFarlands.LOGGER.info("FLREQ requests distinct={} total={} throttled=true", distinct, total);
        }
    }
}
