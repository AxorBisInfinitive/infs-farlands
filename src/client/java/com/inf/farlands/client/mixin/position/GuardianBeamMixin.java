package com.inf.farlands.client.mixin.position;

import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.GuardianRenderer;
import net.minecraft.client.renderer.entity.state.GuardianRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 守卫者状态里的三个点改成同一个锚点的相对量。
 *
 * <p>
 * 三个字段全被当作差值用：渲染器取 {@code attackTargetPosition - eyePosition}，模型取
 * {@code lookAtPosition - eyePosition}。两个世界量级位置相减在 {@code 2^60} 处只剩 0 或 256，光束方向与眼睛朝向
 * 因此全错。把三个点一起减掉守卫者自己的锚点，两处差值的减法仍然成立，消费端一个字节都不用改。
 *
 * <p>
 * 必须全或无：{@code eyePosition} 同时参与两对差值，只平移其中两个会让一处绝对量与世界量混算，比原来的量化
 * 更差。所以只要有一对拿不到精确点，三处全部保留 vanilla 的世界值。
 *
 * <p>
 * 不判量级：锚点为 0 时写法与 vanilla 同值，精度是渐变的。一个字段为空表示 vanilla 这一帧没有那个点，按空处理，
 * 不参与平移也不写。
 */
@Mixin(GuardianRenderer.class)
public class GuardianBeamMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Guardian;Lnet/minecraft/client/renderer/entity/state/GuardianRenderState;F)V", at = @At("RETURN"))
    private void farlands$shiftPoints(Guardian entity, GuardianRenderState state, float partialTicks,
            CallbackInfo ci) {
        if (!(entity instanceof ExactPosition guardian)) {
            return;
        }
        LivingEntity attackTarget = entity.getActiveAttackTarget();
        Entity lookAtEntity = entity.hasActiveAttackTarget() ? attackTarget
                : Minecraft.getInstance().getCameraEntity();
        boolean hasAttack = state.attackTargetPosition != null;
        boolean hasLook = state.lookAtPosition != null;
        if (hasAttack && !(attackTarget instanceof ExactPosition)) {
            return;
        }
        if (hasLook && !(lookAtEntity instanceof ExactPosition)) {
            return;
        }
        long guardianX = guardian.farlands$anchor(ExactPosition.POS_X);
        long guardianY = guardian.farlands$anchor(ExactPosition.POS_Y);
        long guardianZ = guardian.farlands$anchor(ExactPosition.POS_Z);
        state.eyePosition = new Vec3(
                guardian.farlands$lerpLocal(ExactPosition.POS_X, partialTicks),
                guardian.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks) + (double) entity.getEyeHeight(),
                guardian.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks));
        if (hasAttack) {
            ExactPosition target = (ExactPosition) attackTarget;
            double halfHeight = (double) attackTarget.getBbHeight() * 0.5;
            state.attackTargetPosition = new Vec3(
                    FrameMath.offset(target.farlands$anchor(ExactPosition.POS_X),
                            target.farlands$lerpLocal(ExactPosition.POS_X, partialTicks), guardianX, 0.0),
                    FrameMath.offset(target.farlands$anchor(ExactPosition.POS_Y),
                            target.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks) + halfHeight,
                            guardianY, 0.0),
                    FrameMath.offset(target.farlands$anchor(ExactPosition.POS_Z),
                            target.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks), guardianZ, 0.0));
        }
        if (hasLook) {
            ExactPosition look = (ExactPosition) lookAtEntity;
            double eye = (double) lookAtEntity.getEyeHeight();
            state.lookAtPosition = new Vec3(
                    FrameMath.offset(look.farlands$anchor(ExactPosition.POS_X),
                            look.farlands$lerpLocal(ExactPosition.POS_X, partialTicks), guardianX, 0.0),
                    FrameMath.offset(look.farlands$anchor(ExactPosition.POS_Y),
                            look.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks) + eye, guardianY, 0.0),
                    FrameMath.offset(look.farlands$anchor(ExactPosition.POS_Z),
                            look.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks), guardianZ, 0.0));
        }
    }
}
