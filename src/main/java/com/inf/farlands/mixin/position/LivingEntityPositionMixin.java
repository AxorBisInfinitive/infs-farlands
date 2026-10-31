package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.LivingEntity;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LivingEntity 里三处读 {@code xo/yo/zo} 的字段指令改读 256 位槽位。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPositionMixin {

    @Redirect(method = "handleEntityEvent(B)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eventX(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "handleEntityEvent(B)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eventY(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_YO, self.yo);
    }

    @Redirect(method = "handleEntityEvent(B)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eventZ(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }

    @Redirect(method = "calculateEntityAnimation(Z)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$animX(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "calculateEntityAnimation(Z)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$animY(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_YO, self.yo);
    }

    @Redirect(method = "calculateEntityAnimation(Z)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$animZ(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$tickX(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$tickZ(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }
}
