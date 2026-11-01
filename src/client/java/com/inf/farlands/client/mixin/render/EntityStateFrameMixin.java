package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.render.ExactRenderOrigin;
import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在实体渲染状态抽取完成时记下它的帧。
 *
 * <p>
 * 挂在基类方法的返回处：子类的覆写都会调回这里，一帧每个状态各填一次。栅值取 {@code 2^53}，三轴都不够大时
 * 不填，消费点因此逐位退回原式。
 *
 * <p>
 * 局部量用 {@code lerpLocal} 而不是 {@code local}：状态的 {@code x/y/z} 是部分刻插值后的位置，帧要与它同源，
 * 否则模型会跳回上一 tick 的位置。
 */
@Mixin(EntityRenderer.class)
public class EntityStateFrameMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("RETURN"))
    private void farlands$captureOrigin(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        ExactRenderOrigin origin = (ExactRenderOrigin) state;
        if (!(entity instanceof ExactPosition holder)) {
            origin.farlands$clearOrigin();
            return;
        }
        double limit = FrameMath.threshold();
        if (Math.abs(state.x) < limit && Math.abs(state.y) < limit && Math.abs(state.z) < limit) {
            origin.farlands$clearOrigin();
            return;
        }
        origin.farlands$setOrigin(
                holder.farlands$anchor(ExactPosition.POS_X),
                holder.farlands$anchor(ExactPosition.POS_Y),
                holder.farlands$anchor(ExactPosition.POS_Z),
                holder.farlands$lerpLocal(ExactPosition.POS_X, partialTicks),
                holder.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks),
                holder.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks));
    }
}
