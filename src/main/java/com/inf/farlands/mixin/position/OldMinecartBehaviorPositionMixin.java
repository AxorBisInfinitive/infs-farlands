package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 旧矿车行为里 {@code tick} 读 {@code minecart.xo/zo} 的两条指令。
 */
@Mixin(OldMinecartBehavior.class)
public abstract class OldMinecartBehaviorPositionMixin {

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$xo(AbstractMinecart cart) {
        return ExactDoubles.read((ExactPosition) cart, ExactPosition.OLD_XO, cart.xo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$zo(AbstractMinecart cart) {
        return ExactDoubles.read((ExactPosition) cart, ExactPosition.OLD_ZO, cart.zo);
    }
}
