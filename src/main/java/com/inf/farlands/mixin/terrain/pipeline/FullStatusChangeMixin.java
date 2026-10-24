package com.inf.farlands.mixin.terrain.pipeline;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断：完整档确认读数。
 *
 * <p>实体侧的状态通知由本方法发出，而它只在 {@code ChunkHolder} 的确认 future 成功之后才被调。
 * 因此这一行能回答两个问题：某 chunk 到底有没有被确认，以及被确认的是哪一档。`BLOCK_TICKING` 与
 * `ENTITY_TICKING` 在实体侧都映射成 `Visibility.TICKING`，所以只看下游那一行分不开两档，必须在这里读。
 *
 * <p>按 chunk 与档位去重，只在 {@code logScheduler} 打开时输出。定位完成后与同批探针一并删除，
 * 不混进正式改动。
 */
@Mixin(ChunkMap.class)
public class FullStatusChangeMixin {

    /** 已打过的 chunk 与档位组合。 */
    @Unique
    private static final Set<Long> farlands$LOGGED = ConcurrentHashMap.newKeySet();

    @Inject(method = "onFullChunkStatusChange(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V", at = @At("HEAD"))
    private void farlands$logFullStatusChange(ChunkPos pos, FullChunkStatus status, CallbackInfo ci) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        long key = (pos.pack() << 3) | (status.ordinal() & 0x7);
        if (!farlands$LOGGED.add(key)) {
            return;
        }
        InfsFarlands.LOGGER.info("FLPROMO chunk={},{} status={}", pos.x(), pos.z(), status.name());
    }
}
