package com.inf.farlands.client.mixin.position;

import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鱼线的起点偏移改在帧里算。
 *
 * <p>
 * 原式把玩家手部与浮标两个世界量级位置相减，{@code 2^60} 处 ulp 是 256，线长只剩 0 或 256。三个分量存的就是
 * 差值，消费端只读它，所以只需在抽取的返回处重算一次。
 *
 * <p>
 * 该方法的 {@code owner == null} 那一支在方法中途就返回，{@code @At("RETURN")} 会在那里也执行一次，所以
 * 处理体自己取一次 owner 并判空，不拿空引用去算帧。
 *
 * <p>
 * 手部位置不能借用 {@code getPlayerHandPos} 的结果，那个结果已经被量化到世界量级 double。所以
 * {@link #farlands$handOffset} 逐字照抄 {@code FishingHookRenderer.getPlayerHandPos} 的两支，只保留「相对眼睛的
 * 小偏移」那一段，眼睛本体由实体帧给出。vanilla 改这两支时这里要跟着改。
 */
@Mixin(FishingHookRenderer.class)
public class FishingLineMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;F)V", at = @At("RETURN"))
    private void farlands$frameLine(FishingHook entity, FishingHookRenderState state, float partialTicks,
            CallbackInfo ci) {
        Player owner = entity.getPlayerOwner();
        if (owner == null || !(entity instanceof ExactPosition hook)
                || !(owner instanceof ExactPosition hand)) {
            return;
        }
        long handX = hand.farlands$anchor(ExactPosition.POS_X);
        long handY = hand.farlands$anchor(ExactPosition.POS_Y);
        long handZ = hand.farlands$anchor(ExactPosition.POS_Z);
        long hookX = hook.farlands$anchor(ExactPosition.POS_X);
        long hookY = hook.farlands$anchor(ExactPosition.POS_Y);
        long hookZ = hook.farlands$anchor(ExactPosition.POS_Z);
        Vec3 offset = this.farlands$handOffset(owner, partialTicks);        double handLocalX = hand.farlands$lerpLocal(ExactPosition.POS_X, partialTicks) + offset.x;
        double handLocalY = hand.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks)
                + (double) owner.getEyeHeight() + offset.y;
        double handLocalZ = hand.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks) + offset.z;
        double hookLocalY = hook.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks) + 0.25;
        state.lineOriginOffset = new Vec3(
                FrameMath.offset(handX, handLocalX, hookX,
                        hook.farlands$lerpLocal(ExactPosition.POS_X, partialTicks)),
                FrameMath.offset(handY, handLocalY, hookY, hookLocalY),
                FrameMath.offset(handZ, handLocalZ, hookZ,
                        hook.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks)));
    }

    /**
     * 手相对眼睛的小偏移，与 {@code getPlayerHandPos} 同源。
     *
     * <p>
     * 第一人称取近平面上的一点并按视场缩放，第三人称按身体朝向与体型折算。两条都只产出约一格的向量，
     * 与眼睛相加后仍是局部量，因此可以整体带进帧里。
     */
    private Vec3 farlands$handOffset(Player owner, float partialTicks) {
        int invert = FishingHookRenderer.getHoldingArm(owner) == HumanoidArm.RIGHT ? 1 : -1;
        float swing = owner.getAttackAnim(partialTicks);
        float swing2 = Mth.sin(Mth.sqrt(swing) * (float) Math.PI);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.getCameraType().isFirstPerson() && owner == minecraft.player) {
            float fov = minecraft.options.fov().get().intValue();
            double viewBobbingScale = 960.0 / fov;
            return minecraft.getEntityRenderDispatcher().camera
                    .getNearPlane(fov)
                    .getPointOnPlane(invert * 0.525F, -0.1F)
                    .scale(viewBobbingScale)
                    .yRot(swing2 * 0.5F)
                    .xRot(-swing2 * 0.7F);
        }
        float ownerYRot = Mth.lerp(partialTicks, owner.yBodyRotO, owner.yBodyRot) * (float) (Math.PI / 180.0);
        double sin = Mth.sin(ownerYRot);
        double cos = Mth.cos(ownerYRot);
        float playerScale = owner.getScale();
        double rightOffset = invert * 0.35 * playerScale;
        double forwardOffset = 0.8 * playerScale;
        float yOffset = owner.isCrouching() ? -0.1875F : 0.0F;
        return new Vec3(-cos * rightOffset - sin * forwardOffset, yOffset - 0.45 * playerScale,
                -sin * rightOffset + cos * forwardOffset);
    }
}
