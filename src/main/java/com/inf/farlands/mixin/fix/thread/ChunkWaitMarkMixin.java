package com.inf.farlands.mixin.fix.thread;

import com.inf.farlands.terrain.pipeline.Scheduler;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 阻塞取数的等待登记点。
 *
 * <p>取 {@code getChunk} 里调 {@code managedBlock} 的那条指令。它在缓存未命中之后、{@code join} 之前，
 * 所以只在真要阻塞时命中一次，缓存命中的热路径不经过它。目标串的所属类是字节码里的
 * {@code ServerChunkCache$MainThreadExecutor}，不是 {@code managedBlock} 的声明类
 * {@code BlockableEventLoop}。
 *
 * <p>这一处只写一个占位，不登记也不铺票：绝大多数 FULL 取数会立即返回，在这里做事等于给每一格都铺一张
 * 结构票。真正的登记、提交与铺票由 {@link Scheduler} 在等待被确认之后做。
 *
 * <p>只登记 FULL。其余状态各有自己的完成者，本类不替它们表态。
 */
@Mixin(ServerChunkCache.class)
public abstract class ChunkWaitMarkMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"))
    private void farlands$noteBlockingGet(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (targetStatus == ChunkStatus.FULL) {
            Scheduler.noteBlockingFullGet(this.level, x, z);
        }
    }

    /** 取数返回时撤占位。没过门槛的短等待必须在这里收掉，否则它会永久占住唯一的位置。 */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("RETURN"))
    private void farlands$clearPendingGet(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (targetStatus == ChunkStatus.FULL) {
            Scheduler.clearPendingWait(this.level, x, z);
        }
    }
}
