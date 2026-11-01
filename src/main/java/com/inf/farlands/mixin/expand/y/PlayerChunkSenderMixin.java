package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.PlayerTable;
import com.inf.farlands.util.window.WindowSendState;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把发送时刻的玩家窗口写进 WindowSendState，取代建 chunk 时的窗口快照；并在这个包发不出 section
 * 时留下补发标记。
 *
 * sendChunk 是 private static，每个 chunk 调一次，PlayerChunkSender 按玩家各自持有队列；
 * HEAD 设置该玩家的窗口 minY，RETURN 清除。
 *
 * <p>
 * 生成、光照或 biome 阶段在途时 {@code WindowSendState.sendableSections} 返回空列表，这个 chunk 包
 * 一个 section 都不带；而 fill 与读回的补发入队都发生在更早的时刻，玩家当时可能还不在范围内，
 * 于是该 chunk 会一直是空壳。这里在同样的判据下标记一次内容变化，由 ChunkDataSender 按玩家当前
 * 窗口补发。
 */
@Mixin(PlayerChunkSender.class)
public class PlayerChunkSenderMixin {

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void captureWindow(ServerGamePacketListenerImpl packetListener, ServerLevel level, LevelChunk chunk,
            CallbackInfo ci) {
        ServerPlayer player = packetListener.player;
        PlayerTable.Entry entry = PlayerTable.entryOf(player);
        if (entry != null && !entry.inBounds()) {
            // 界外没有区块：给一个空窗口，sendableSections 的区间判据因此恒假，段包不带任何段。
            WindowSendState.setWindowMinY(Integer.MAX_VALUE);
        } else {
            int centerY = entry != null ? entry.sectionCenterY() : Mth.floorDiv(player.getBlockY(), 16);
            WindowSendState.setWindowMinY(centerY - ((WindowedChunk) chunk).windowHalfBelow());
        }
        if (GenQueue.isChunkBusy(chunk) || GenQueue.isBiomeFilling(chunk)) {
            ChunkDataSender.markChunkChanged(chunk);
        }
    }

    @Inject(method = "sendChunk", at = @At("RETURN"))
    private static void clearWindow(ServerGamePacketListenerImpl packetListener, ServerLevel level, LevelChunk chunk,
            CallbackInfo ci) {
        WindowSendState.clear();
    }
}
