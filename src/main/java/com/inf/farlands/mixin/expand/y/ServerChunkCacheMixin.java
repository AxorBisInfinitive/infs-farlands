package com.inf.farlands.mixin.expand.y;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.SavedDataStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Shadow
    private ThreadedLevelLightEngine lightEngine;

    @Shadow
    private SavedDataStorage savedDataStorage;

    @Shadow
    public ChunkMap chunkMap;

    @Shadow
    public void save(boolean flush) {
    }

    @Overwrite
    public void close() throws IOException {
        try {
            this.save(true);
        } finally {
            this.savedDataStorage.close();
            this.lightEngine.close();
            this.chunkMap.close();
        }
    }

    /**
     * 入场期的出生区等待不跟数据走。
     *
     * <p>addTicketAndLoadWithRadius 取的是 getChunkRangeFuture 的 FULL 范围，而 PrepareSpawnTask 与
     * PlayerSpawnFinder 在玩家入场之前就等它，此时没有任何玩家窗口，数据就绪永远不成立。模板里 FULL
     * 本来是立即成功的，所以这里直接给一个已完成的 future，等价于改动前的时点。
     */
    @Redirect(method = "addTicketAndLoadWithRadius", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<List<ChunkAccess>>> farlands$skipDataWait(ChunkMap chunkMap,
            ChunkHolder chunkHolder, int radius, IntFunction<ChunkStatus> distanceToStatus) {
        return CompletableFuture.completedFuture(ChunkResult.of(List.<ChunkAccess>of()));
    }

    /**
     * 可玩界之外的 chunk、以及本维度没有窗口时的 FULL 请求，同步取块直接给 null，不进入阻塞等待。
     *
     * <p>
     * 两条判据都指向同一件事：这个 future 永远不会完成，等下去就是主线程被按住。界外那一条是 chunk 坐标落在
     * {@code ±MAX_PLAYABLE_SECTION} 之外，与 {@code ChunkPos.isValid} 同源；窗口那一条是该维度本 tick 没有界内
     * 玩家，调度器的准入没有段可派，第七行因此恒假。实测路径是玩家在 {@code 2147483647} 处时，{@code Entity.move}
     * 的碰撞枚举经 {@code getChunkForCollisions} 问到自己的 chunk，以及撑大后的碰撞盒扫到相邻的可玩界内 chunk。
     *
     * <p>
     * 窗口那一条只对 {@code FULL} 生效。FULL 以下的状态在 {@code farlandsCompleteEmpty} 里一次性完成，没有窗口也
     * 会落定，不存在死等；只有 FULL 的门是数据就绪。
     *
     * <p>
     * 只挡 {@code loadOrGenerate} 为假那一路。为真的那条语义是「要求生成」，它的入口在 {@code Level.getBlockState}、
     * {@code setBlock} 这些地方已由 {@code isInValidBounds} 挡在更前面，出生点搜索那条 {@code PlayerSpawnFinder}
     * 走的是两参 {@code getChunk}，即 {@code loadOrGenerate = true}，也不受影响。为真时返回 null 会让
     * {@code Level.getChunk} 抛 {@code Should always be able to create a chunk!}，代价不成比例。
     *
     * <p>
     * 全部 {@code false} 调用点都容忍 null：{@code BlockCollisions} 跳过该 chunk、{@code getNoiseBiome} 落未缓存支、
     * {@code FillBiomeCommand} 报未加载、{@code hasChunk} 判据本身就是非空。
     *
     * <p>
     * 窗口判据是近似的：{@code sectionsFor} 的范围是「显式范围 ∪ 驱动面 ∪ 窗口」，没有窗口但有驱动面时仍可能完成。
     * 那种情形只出现在玩家进场之前，驱动面由出生区预加载登记，而那条路径的阻塞等待是 {@code loadOrGenerate = true}。
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void farlands$rejectUnreachable(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (loadOrGenerate) {
            return;
        }
        if (!WorldBounds.inChunkRange(x, z)) {
            cir.setReturnValue(null);
            return;
        }
        if (targetStatus == ChunkStatus.FULL
                && EntitySectionWindow.ranges(((ServerChunkCache) (Object) this).getLevel().dimension()).length == 0) {
            cir.setReturnValue(null);
        }
    }
}
