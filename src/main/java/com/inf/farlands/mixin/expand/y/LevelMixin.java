package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Overwrite
    public boolean shouldTickBlocksAt(BlockPos pos) {
        if ((Object) this instanceof ServerLevel
                && !EntitySectionWindow.inAnyWindow(((Level) (Object) this).dimension(), pos.getY() >> 4)) {
            return false;
        }
        return ((Level) (Object) this).shouldTickBlocksAt(ChunkPos.pack(pos));
    }

    @SuppressWarnings("resource")
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void rejectOutsideOrUnready(BlockPos pos, BlockState state, int flags, int recursionLeft,
            CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            return;
        }
        int sectionY = pos.getY() >> 4;
        if (EntitySectionWindow.isOutsideAllWindows(self.dimension(), sectionY,
                FarlandsConfig.sectionCleanupMargin)) {
            cir.setReturnValue(false);
            return;
        }
        // 窗口内但数据未就绪：写下去会被随后的 fill 覆盖，或落进还没读回的空段。拒绝的后果由调用方
        // 承担，命令报 commands.setblock.failed，玩家放置表现为放不下去。
        //
        // 读回那一条按段判，不按 chunk：窗口滑动一次会给整片 tracking view 的每个 chunk 打上边缘段的
        // 读回标记，按 chunk 判会把玩家脚下那一整块的写入一起拒掉，直到那些远段的读回排完队。
        if (self instanceof ServerLevel serverLevel
                && !ChunkReadiness.isReady(serverLevel, ChunkPos.containing(pos), sectionY)) {
            cir.setReturnValue(false);
        }
    }
}