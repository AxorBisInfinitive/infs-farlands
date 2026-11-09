package com.inf.farlands.client.mixin.position;

import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 本地玩家的位移改在帧里算，供自动跳跃与手部摆动使用。
 *
 * <p>
 * {@code LocalPlayer.move} 前后各取一次 {@code getX()}，相减得位移。两个读数都是世界量级的 double 投影，
 * {@code 5e18} 处 ulp 是 1024，位移恒为 0，于是 {@code addWalkedDistance} 收到 0，手部摆动不前进，表现为
 * 移动时手臂效果不对。
 *
 * <p>
 * 这里不覆写整个方法：{@code move} 的父类调用与两边夹着的采集都要保留。只在 HEAD 记一次帧，再把两处消费的
 * 位移换成帧内差值。锚点全零时，帧内差值等于原来的 double 相减，正常坐标逐位不变。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerPositionMixin {

    @Shadow
    protected abstract void updateAutoJump(float deltaX, float deltaZ);

    @Unique
    private long farlands$beforeAnchorX;

    @Unique
    private long farlands$beforeAnchorZ;

    @Unique
    private double farlands$beforeLocalX;

    @Unique
    private double farlands$beforeLocalZ;

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void farlands$captureBeforeMove(MoverType moverType, Vec3 delta, CallbackInfo ci) {
        ExactPosition holder = (ExactPosition) (Object) this;
        this.farlands$beforeAnchorX = holder.farlands$anchor(ExactPosition.POS_X);
        this.farlands$beforeAnchorZ = holder.farlands$anchor(ExactPosition.POS_Z);
        this.farlands$beforeLocalX = holder.farlands$local(ExactPosition.POS_X);
        this.farlands$beforeLocalZ = holder.farlands$local(ExactPosition.POS_Z);
    }

    /** 自动跳跃的位移换成帧内差值。 */
    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;updateAutoJump(FF)V"))
    private void farlands$updateAutoJump(LocalPlayer self, float deltaX, float deltaZ) {
        this.updateAutoJump((float) this.farlands$delta(ExactPosition.POS_X),
                (float) this.farlands$delta(ExactPosition.POS_Z));
    }

    /** 手部摆动的距离换成帧内差值，{@code * 0.6F} 留在原语句里。 */
    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;length(FF)F"))
    private float farlands$walkDistance(float deltaX, float deltaZ) {
        return Mth.length((float) this.farlands$delta(ExactPosition.POS_X),
                (float) this.farlands$delta(ExactPosition.POS_Z));
    }

    /** 该轴自 HEAD 记下的帧到现在的位移，锚点差加局部差。 */
    @Unique
    private double farlands$delta(int axis) {
        ExactPosition holder = (ExactPosition) (Object) this;
        long anchorBefore = axis == ExactPosition.POS_X ? this.farlands$beforeAnchorX : this.farlands$beforeAnchorZ;
        double localBefore = axis == ExactPosition.POS_X ? this.farlands$beforeLocalX : this.farlands$beforeLocalZ;
        return (double) (holder.farlands$anchor(axis) - anchorBefore) + (holder.farlands$local(axis) - localBefore);
    }
}
