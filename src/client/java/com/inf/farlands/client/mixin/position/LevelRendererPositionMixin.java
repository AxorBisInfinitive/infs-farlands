package com.inf.farlands.client.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 客户端每帧给实体写旧位置的三条指令，写槽位后回写字段。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPositionMixin {

    @Redirect(method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$xOld(Entity entity, double value) {
        ExactDoubles.write((ExactPosition) entity, ExactPosition.OLD_X, value);
        entity.xOld = value;
    }

    @Redirect(method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$yOld(Entity entity, double value) {
        ExactDoubles.write((ExactPosition) entity, ExactPosition.OLD_Y, value);
        entity.yOld = value;
    }

    @Redirect(method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$zOld(Entity entity, double value) {
        ExactDoubles.write((ExactPosition) entity, ExactPosition.OLD_Z, value);
        entity.zOld = value;
    }
}
