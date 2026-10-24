package com.inf.farlands.mixin.terrain.pipeline;

import java.util.concurrent.CompletableFuture;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断：实体 chunk 读盘的落地读数。
 *
 * <p>{@code loadEntities} 是两跳结构：先在 {@code simpleRegionStorage.read} 上等读盘，再
 * {@code thenApplyAsync} 到服务端执行器做反序列化。本类把返回值包一层，完成与异常各打一条，
 * 于是「读盘没回」与「反序列化那一跳没跑」可以分开。它与实体侧的 `PENDING` 合看：请求发出而这一行
 * 不出现，就说明停在第一跳之前。
 *
 * <p>不改语义，只是挂一次回调。只在 {@code logScheduler} 打开时输出。定位完成后与同批探针一并删除。
 */
@Mixin(EntityStorage.class)
public class EntityChunkLoadMixin {

    @Inject(method = "loadEntities", at = @At("RETURN"), cancellable = true)
    private void farlands$wrapEntitiesLoad(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        CompletableFuture<?> original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        cir.setReturnValue(original.whenComplete((value, error) -> {
            if (error != null) {
                InfsFarlands.LOGGER.info("FLLOAD chunk={},{} done=error err={}", pos.x(), pos.z(),
                        error.toString());
            } else {
                InfsFarlands.LOGGER.info("FLLOAD chunk={},{} done=ok", pos.x(), pos.z());
            }
        }));
    }
}
