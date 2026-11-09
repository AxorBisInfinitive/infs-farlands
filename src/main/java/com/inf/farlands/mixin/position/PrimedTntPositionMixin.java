package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.item.PrimedTnt;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 点燃的 TNT 只在构造器里写 {@code xo/yo/zo}。
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntPositionMixin {

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/PrimedTnt;xo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putXo(PrimedTnt self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_XO, value);
        self.xo = value;
    }

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/PrimedTnt;yo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putYo(PrimedTnt self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_YO, value);
        self.yo = value;
    }

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/PrimedTnt;zo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putZo(PrimedTnt self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_ZO, value);
        self.zo = value;
    }
}
