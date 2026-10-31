package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 细雪里判定「是否在移动」时读 {@code entity.xOld/zOld} 的两条指令。
 */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockPositionMixin {

    @Redirect(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xOld:D", opcode = Opcodes.GETFIELD))
    private static double farlands$xOld(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_X, entity.xOld);
    }

    @Redirect(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zOld:D", opcode = Opcodes.GETFIELD))
    private static double farlands$zOld(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_Z, entity.zOld);
    }
}
