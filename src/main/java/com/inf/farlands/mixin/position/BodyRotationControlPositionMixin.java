package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.BodyRotationControl;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 身体转向控制里 {@code isMoving} 读 {@code mob.xo/zo} 的两条指令。
 *
 * <p>
 * 所属类是 {@code Mob}：接收者 {@code this.mob} 的静态类型就是它。
 */
@Mixin(BodyRotationControl.class)
public abstract class BodyRotationControlPositionMixin {

    @Redirect(method = "isMoving()Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Mob;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$xo(Mob mob) {
        return ExactDoubles.read((ExactPosition) mob, ExactPosition.OLD_XO, mob.xo);
    }

    @Redirect(method = "isMoving()Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Mob;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$zo(Mob mob) {
        return ExactDoubles.read((ExactPosition) mob, ExactPosition.OLD_ZO, mob.zo);
    }
}
