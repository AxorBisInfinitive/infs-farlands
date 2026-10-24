package com.inf.farlands.client.mixin.expand.y;

import com.inf.farlands.client.TintInvalidator;
import com.inf.farlands.util.window.WindowedChunk;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端窗口外 setBlock 直接拒绝，与主源集 LevelMixin 的服务端那一份对称。
 *
 * 那份在 isClientSide 时提前返回，所以客户端这条路径原本无人拦。窗口外的写入会被后续的
 * 窗口帧丢弃，形成写入-丢弃循环，源头拒掉更干净。正常交互位置必在窗口内：交互距离远小于
 * 窗口跨度，拒只会发生在物理连锁传播出窗口时，服务端权威最终修正。
 * setServerVerifiedBlockState 走的是 super.setBlock，绕过本注入，服务端包照常生效。
 *
 * <p>另外把 {@code tintCaches} 暴露给 {@link TintInvalidator}：段数据晚于邻居染色查询到达时，
 * 需要有人在数据到位的那一刻清掉邻域的颜色缓存。
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin implements TintInvalidator {

    /** 四条染色解析器各自的按列缓存，{@code BlockTintCache.invalidateForChunk} 自带 3×3 邻域。 */
    @Shadow
    @Final
    private Object2ObjectArrayMap<ColorResolver, BlockTintCache> tintCaches;

    @Override
    public void invalidateTintAround(int chunkX, int chunkZ) {
        for (BlockTintCache cache : this.tintCaches.values()) {
            cache.invalidateForChunk(chunkX, chunkZ);
        }
    }

    @SuppressWarnings("resource")
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void farlands$rejectOutsideWindow(BlockPos pos, BlockState state, int updateFlags, int updateLimit,
            CallbackInfoReturnable<Boolean> cir) {
        ClientLevel self = (ClientLevel) (Object) this;
        LevelChunk chunk = self.getChunkSource().getChunk(
                pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        int sectionY = pos.getY() >> 4;
        WindowedChunk wc = (WindowedChunk) chunk;
        if (sectionY < wc.getWindowMinY() || sectionY > wc.getWindowMaxY()) {
            cir.setReturnValue(false);
        }
    }
}
