package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.monster.EnderMan;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 末影人 {@code teleport} 里读 {@code xo/yo/zo} 播音效的三条指令。
 */
@Mixin(EnderMan.class)
public abstract class EnderManPositionMixin {

    @Redirect(method = "teleport(DDD)Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/EnderMan;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$xo(EnderMan self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "teleport(DDD)Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/EnderMan;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$yo(EnderMan self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_YO, self.yo);
    }

    @Redirect(method = "teleport(DDD)Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/EnderMan;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$zo(EnderMan self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }
}
