package com.inf.farlands.mixin.terrain.pipeline;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断：chunk 档位跃迁读数。
 *
 * <p>实体侧的状态通知只在 {@code updateFutures} 的跃迁分支里发出，所以「有没有通知」这个问题要先在
 * 这一层回答：某 chunk 的 {@code oldTicketLevel} 与 {@code ticketLevel} 换算出的完整档有没有变过。
 * 构造器把两者都置成 {@code MAX_LEVEL + 1}，因此首轮调用必然看到一次下降沿；若某个 chunk 从这里之后
 * 再无下降沿，那它的通知就只可能来自首轮那一次，而首轮那一次的确认要等对应的 future 成功。
 *
 * <p>只打有跃迁的那些，另每 256 次调用打一条汇总作有效性证据。只在 {@code logScheduler} 打开时输出。
 * 定位完成后与同批探针一并删除，不混进正式改动。
 */
@Mixin(ChunkHolder.class)
public class ChunkHolderTransitionMixin {

    @Shadow
    private int oldTicketLevel;

    @Shadow
    private int ticketLevel;

    /** 调用次数，汇总行用。 */
    @Unique
    private static final AtomicInteger farlands$CALLS = new AtomicInteger();

    /** 有完整档跃迁的次数，汇总行用。 */
    @Unique
    private static final AtomicInteger farlands$CHANGES = new AtomicInteger();

    /** 汇总行的间隔。 */
    @Unique
    private static final int farlands$SUMMARY_EVERY = 256;

    @Inject(method = "updateFutures(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/Executor;)V", at = @At("HEAD"))
    private void farlands$logTransition(ChunkMap scheduler, Executor mainThreadExecutor, CallbackInfo ci) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        int calls = farlands$CALLS.incrementAndGet();
        FullChunkStatus oldStatus = ChunkLevel.fullStatus(this.oldTicketLevel);
        FullChunkStatus newStatus = ChunkLevel.fullStatus(this.ticketLevel);
        if (oldStatus != newStatus) {
            farlands$CHANGES.incrementAndGet();
            InfsFarlands.LOGGER.info("FLXFER chunk={},{} old={} new={} oldSt={} newSt={} calls={}",
                    ((ChunkHolder) (Object) this).getPos().x(), ((ChunkHolder) (Object) this).getPos().z(),
                    this.oldTicketLevel, this.ticketLevel, oldStatus, newStatus, calls);
        }
        if (calls % farlands$SUMMARY_EVERY == 0) {
            InfsFarlands.LOGGER.info("FLXFER summary calls={} changes={}", calls, farlands$CHANGES.get());
        }
    }
}
