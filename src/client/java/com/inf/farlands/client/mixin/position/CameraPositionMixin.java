package com.inf.farlands.client.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 相机每帧插值读 {@code entity.xo/yo/zo} 的三条指令，改读 256 位槽位。
 */
@Mixin(Camera.class)
public abstract class CameraPositionMixin {

    @Redirect(method = "alignWithEntity(F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$xo(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_XO, entity.xo);
    }

    @Redirect(method = "alignWithEntity(F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$yo(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_YO, entity.yo);
    }

    @Redirect(method = "alignWithEntity(F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$zo(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_ZO, entity.zo);
    }
}
