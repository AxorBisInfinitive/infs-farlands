package com.inf.farlands.mixin.serialize;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.pipeline.NeighborhoodTickets;
import com.inf.farlands.terrain.pipeline.OwnershipTable;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.terrain.pipeline.SpawnPreload;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.network.SystemsSender;
import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fsa 的关服同步刷盘。
 *
 * 等价位点是 MinecraftServer.stopServer() 的 HEAD，它在最终保存之前执行，此时各维度与 chunk
 * 都还在。
 *
 * 顺序不可调换。卸载编码任务由 flushChunk 提交到 ENCODE_POOL，必须先于 awaitIODrain 完成
 * 提交，否则其 IO 写不被等待，关服就丢已卸载的 section 数据。
 *
 * GenQueue.awaitIdle 在 IO 排空之后、同步兜底写之前，等在途生成与光照收敛，有界 5 秒。
 * 超时后的在途 section 由 shutdownSyncFlush 的 isChunkBusy 跳过兜底，重进重生成。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void farlands$fsaShutdownFlush(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        ChunkReadiness.markShuttingDown();
        try {
            SectionLifecycle.awaitEncodeTasks(server, 5000);
            SectionIO.awaitIODrain();
            SectionIO.drainMainThreadTasks(server);
            GenQueue.awaitIdle(server, 5000);
            SectionLifecycle.shutdownSyncFlush(server);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("farlands: fsa shutdown flush failed", e);
        }
    }

    /**
     * 停服后清掉按世界的进程级状态。
     *
     * <p>必须挂在 RETURN 而不是 HEAD：HEAD 那一段要跑 shutdownSyncFlush，它依赖 SectionStage 的阶段；
     * 而存盘门在关服期只判在途，否则 vanilla 在 saveAllChunks 里那次 chunk NBT 保存会挡掉 forceload
     * 且无玩家那类 chunk。RETURN 在 saveAllChunks、level.close 与几个 close 之后，线程随后才结束；客户端
     * 在 disconnect 里自旋等 isShutdown()，所以这里既晚于所有需要这些状态的动作，又早于新世界开始。
     *
     * <p>三张位置侧信道表不在这里清：它们在主源集两端共用，客户端那条收尾中的渲染链要到
     * updateLevelInEngines(null) 才释放 sectionNode 长键，清早了会把哈希当坐标。那三张表由客户端卸关卡时清。
     */
    @Inject(method = "stopServer", at = @At("RETURN"))
    private void farlands$clearWorldState(CallbackInfo ci) {
        SectionLifecycle.clearWorldState();
        GenQueue.clearWorldState();
        DecorationFiller.clearWorldState();
        OwnershipTable.clearWorldState();
        Scheduler.clearWorldState();
        StructureDriver.clearWorldState();
        ChunkDataSender.clearWorldState();
        SpawnPreload.clearAll();
        NeighborhoodTickets.clearWorldState();
        SystemsSender.clearWorldState();
        ChunkReadiness.clearAll();
        SectionStage.clearAll();
        EntitySectionWindow.clear();
        SectionIO.clearCache();
    }
}
