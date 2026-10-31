package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 船的 {@code setInitialPos} 写 {@code xo/yo/zo}。
 */
@Mixin(AbstractBoat.class)
public abstract class AbstractBoatPositionMixin {

    @Redirect(method = "setInitialPos(DDD)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;xo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putXo(AbstractBoat self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_XO, value);
        self.xo = value;
    }

    @Redirect(method = "setInitialPos(DDD)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;yo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putYo(AbstractBoat self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_YO, value);
        self.yo = value;
    }

    @Redirect(method = "setInitialPos(DDD)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;zo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putZo(AbstractBoat self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_ZO, value);
        self.zo = value;
    }
}
