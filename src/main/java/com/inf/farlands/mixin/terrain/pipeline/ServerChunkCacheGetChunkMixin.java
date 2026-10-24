package com.inf.farlands.mixin.terrain.pipeline;

import com.inf.farlands.debug.FreezeWatch;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断：登记每一次阻塞式取 chunk。
 *
 * <p>只登记进入与离开，不打印。打印点在 {@code MainThreadExecutor.pollTask} 那一侧，因为它才是等待
 * 期间真正被反复执行的循环体。本类唯一的判断是把 level 取出来，其余交给 {@link FreezeWatch}。
 *
 * <p>缓存命中也会走到这里，代价是两次线程局部的压栈与出栈。定位完成后与同批探针一并删除。
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheGetChunkMixin {

    /** 取 level 的形状与 {@code ServerChunkCache.getLevel} 一致，后者返回 {@code Level}。 */
    @Unique
    private static ServerLevel farlands$levelOf(ServerChunkCache self) {
        return self.getLevel() instanceof ServerLevel level ? level : null;
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"))
    private void farlands$enterGetChunk(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate,
            CallbackInfoReturnable<ChunkAccess> cir) {
        FreezeWatch.enter(farlands$levelOf((ServerChunkCache) (Object) this), x, z, targetStatus);
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("RETURN"))
    private void farlands$exitGetChunk(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate,
            CallbackInfoReturnable<ChunkAccess> cir) {
        FreezeWatch.exit();
    }
}
