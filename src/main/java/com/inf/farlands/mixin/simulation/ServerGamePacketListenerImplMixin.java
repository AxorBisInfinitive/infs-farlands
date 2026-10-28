package com.inf.farlands.mixin.simulation;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 可玩界之外的移动不校验碰撞，无条件信任客户端报来的位置。
 *
 * <p>
 * 客户端有一块纯客户端的固定方块，位于 2147483650，服务端没有它的碰撞形状，也不打算有。玩家站上去时
 * 客户端算出的位置服务端无法复现，所以把 {@code handleMovePlayer} 里三处会导致回拉的判据在可玩界之外
 * 全部放行：超快移动判据、{@code moved wrongly} 的位移阈值、以及新碰撞检测。
 *
 * <p>
 * 落地只走这一条：判据放行之后位置由 {@code absSnapTo} 采用客户端报来的目标，{@code onGround} 取客户端
 * 报文里的标记，服务端不重新计算。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    /** 超快移动判据在可玩界之外不启用。 */
    @Inject(method = "shouldCheckPlayerMovement", at = @At("RETURN"), cancellable = true)
    private void farlands$trustOutsideBounds(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
        if (farlands$outsideBounds(this.player.getY())) {
            cir.setReturnValue(false);
        }
    }

    /** 新碰撞检测在可玩界之外一律判成没有新碰撞。 */
    @Inject(method = "isEntityCollidingWithAnythingNew", at = @At("RETURN"), cancellable = true)
    private void farlands$noNewCollisionOutsideBounds(LevelReader level, Entity entity, AABB oldAABB,
            double newX, double newY, double newZ, CallbackInfoReturnable<Boolean> cir) {
        if (farlands$outsideBounds(newY)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * {@code moved wrongly} 的阈值在可玩界之外抬到无穷，判据恒假，那一支因此不进回拉分支。
     *
     * <p>
     * 处理体在 {@code player.move} 之后求值，此时 {@code player} 的位置已经落在目标坐标附近。
     */
    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(doubleValue = 0.0625))
    private double farlands$trustMovedWrongly(double original) {
        return farlands$outsideBounds(this.player.getY()) ? Double.POSITIVE_INFINITY : original;
    }

    /** 该 y 是否在可玩方块界之外。 */
    @Unique
    private static boolean farlands$outsideBounds(double y) {
        return !WorldBounds.inBlock((long) Math.floor(y));
    }
}
