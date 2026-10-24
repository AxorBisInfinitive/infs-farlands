package com.inf.farlands.mixin.terrain.pipeline;

import com.inf.farlands.serialize.ReadyEntityChunks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 入场等待之前同步实体 chunk 状态。
 *
 * <p>挂在这里是因为等待的中心只有此刻才确定：它是 {@code PlayerSpawnFinder.findSpawn} 的结果，而那次
 * 搜索本身要等 chunk，登录之前算不出来。逻辑在 {@link ReadyEntityChunks}。
 */
@Mixin(ServerLevel.class)
public class ReadyEntityChunksMixin {

    @Inject(method = "waitForEntities(Lnet/minecraft/world/level/ChunkPos;I)V", at = @At("HEAD"))
    private void farlands$syncEntityChunks(ChunkPos centerChunk, int radius, CallbackInfo ci) {
        ReadyEntityChunks.sync((ServerLevel) (Object) this, centerChunk, radius);
    }
}
