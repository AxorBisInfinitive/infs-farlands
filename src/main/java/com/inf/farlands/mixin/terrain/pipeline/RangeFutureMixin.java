package com.inf.farlands.mixin.terrain.pipeline;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断：区间 future 的取数读数。
 *
 * <p>完整档的确认挂在 {@code getChunkRangeFuture} 的返回值上，而那一层只要有一格不在场、或某格的
 * 任务 future 解析成错误，整体就解析成错误，确认因此永不完成。本类把两处失败出口都读出来：哪一格
 * 不在场，哪一格解析成错误，以及调用它的中心是谁。据此可以算出每一格到底卡在 5 乘 5 的哪个位置。
 *
 * <p>只打失败项与周期性汇总，只在 {@code logScheduler} 打开时输出，且中心已不在实体刻档的调用整条
 * 跳过。定位完成后与同批探针一并删除，不混进正式改动。
 */
@Mixin(ChunkMap.class)
public class RangeFutureMixin {

    /** 正在求值的区间：中心 x、中心 z、是否记录。线程局部，因为池线程也会求值。 */
    @Unique
    private static final ThreadLocal<int[]> farlands$CONTEXT = new ThreadLocal<>();

    /** 求值次数，汇总行用。 */
    @Unique
    private static final AtomicInteger farlands$CALLS = new AtomicInteger();

    /** 失败次数，汇总行用。 */
    @Unique
    private static final AtomicInteger farlands$FAILURES = new AtomicInteger();

    /** 汇总行的间隔。 */
    @Unique
    private static final int farlands$SUMMARY_EVERY = 256;

    @Inject(method = "getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void farlands$enterRange(ChunkHolder centerChunk, int range,
            java.util.function.IntFunction<ChunkStatus> distanceToStatus, CallbackInfoReturnable<?> cir) {
        int[] context = { centerChunk.getPos().x(), centerChunk.getPos().z(),
                FarlandsConfig.logScheduler ? 1 : 0 };
        farlands$CONTEXT.set(context);
        if (context[2] == 1) {
            int calls = farlands$CALLS.incrementAndGet();
            if (calls % farlands$SUMMARY_EVERY == 0) {
                InfsFarlands.LOGGER.info("FLRANGE summary calls={} failures={}", calls,
                        farlands$FAILURES.get());
            }
        }
    }

    @Inject(method = "getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void farlands$exitRange(ChunkHolder centerChunk, int range,
            java.util.function.IntFunction<ChunkStatus> distanceToStatus, CallbackInfoReturnable<?> cir) {
        farlands$CONTEXT.remove();
    }

    /** 5 乘 5 里某一格取不到 holder：这一支会让整个区间立刻解析成错误。 */
    @Redirect(method = "getChunkRangeFuture", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getUpdatingChunkIfPresent(J)Lnet/minecraft/server/level/ChunkHolder;"))
    private ChunkHolder farlands$logAbsentCell(ChunkMap self, long chunkNode) {
        ChunkHolder holder = self.getUpdatingChunkIfPresent(chunkNode);
        int[] context = farlands$CONTEXT.get();
        if (holder == null && context != null && context[2] == 1) {
            farlands$FAILURES.incrementAndGet();
            ChunkPos cell = ChunkPos.unpack(chunkNode);
            InfsFarlands.LOGGER.info("FLRANGE absent center={},{} cell={},{}",
                    context[0], context[1], cell.x(), cell.z());
        }
        return holder;
    }

    /** 某一格的 FULL 任务 future：完成时若没有结果，就是这一格把区间拖成错误。 */
    @Redirect(method = "getChunkRangeFuture", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;scheduleChunkGenerationTask(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/server/level/ChunkMap;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<ChunkAccess>> farlands$logDepFuture(ChunkHolder self,
            ChunkStatus status, ChunkMap scheduler) {
        CompletableFuture<ChunkResult<ChunkAccess>> future = self.scheduleChunkGenerationTask(status, scheduler);
        int[] context = farlands$CONTEXT.get();
        if (context == null || context[2] != 1 || future == null) {
            return future;
        }
        ChunkPos cell = self.getPos();
        return future.whenComplete((result, error) -> {
            if (error != null || result == null || result.orElse(null) == null) {
                farlands$FAILURES.incrementAndGet();
                InfsFarlands.LOGGER.info("FLRANGE dep center={},{} cell={},{} status={} result={}",
                        context[0], context[1], cell.x(), cell.z(), status.getName(),
                        error != null ? "error:" + error : "empty");
            }
        });
    }
}
