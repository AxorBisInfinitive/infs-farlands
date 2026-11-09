package com.inf.farlands.client.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.entity.GuardianRenderer;
import net.minecraft.world.entity.LivingEntity;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 守卫者渲染取位置的旧位置三轴。
 */
@Mixin(GuardianRenderer.class)
public abstract class GuardianRendererPositionMixin {

    @Redirect(method = "getPosition(Lnet/minecraft/world/entity/LivingEntity;DF)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;xOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$xOld(LivingEntity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_X, entity.xOld);
    }

    @Redirect(method = "getPosition(Lnet/minecraft/world/entity/LivingEntity;DF)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;yOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$yOld(LivingEntity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_Y, entity.yOld);
    }

    @Redirect(method = "getPosition(Lnet/minecraft/world/entity/LivingEntity;DF)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;zOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$zOld(LivingEntity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_Z, entity.zOld);
    }
}
