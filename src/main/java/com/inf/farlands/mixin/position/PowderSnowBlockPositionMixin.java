package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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

    /**
     * 雪片粒子的「是否在移动」从两个投影不等改成帧内位移非零。
     *
     * <p>
     * 原式的两端都是量化过的 double，`5e18` 处多半相等，粒子于是不生成。`isMoving` 是该方法的槽位 8 的
     * boolean；判断需要实体，所以处理体追加前四个实参，第四个就是 `entity`。
     */
    @ModifyVariable(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V", at = @At("STORE"), index = 8)
    private boolean farlands$isMoving(boolean value, BlockState state, Level level, BlockPos pos, Entity entity) {
        ExactPosition holder = (ExactPosition) entity;
        return holder.farlands$frameDelta(ExactPosition.POS_X) != 0.0
                || holder.farlands$frameDelta(ExactPosition.POS_Z) != 0.0;
    }
}
