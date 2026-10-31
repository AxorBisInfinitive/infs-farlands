package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 末影龙 {@code aiStep} 里给每节身体写旧位置的六条指令。
 *
 * <p>
 * 字段访问的所属类是 {@code EnderDragonPart} 而不是 {@code Entity}：字节码里的 owner 取接收者的静态类型，
 * 这里接收者是 {@code subEntities[i]}。
 */
@Mixin(EnderDragon.class)
public abstract class EnderDragonPositionMixin {

    @Redirect(method = "aiStep()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;xo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$xo(EnderDragonPart part, double value) {
        ExactDoubles.write((ExactPosition) part, ExactPosition.OLD_XO, value);
        part.xo = value;
    }

    @Redirect(method = "aiStep()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;yo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$yo(EnderDragonPart part, double value) {
        ExactDoubles.write((ExactPosition) part, ExactPosition.OLD_YO, value);
        part.yo = value;
    }

    @Redirect(method = "aiStep()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;zo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$zo(EnderDragonPart part, double value) {
        ExactDoubles.write((ExactPosition) part, ExactPosition.OLD_ZO, value);
        part.zo = value;
    }

    @Redirect(method = "aiStep()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;xOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$xOld(EnderDragonPart part, double value) {
        ExactDoubles.write((ExactPosition) part, ExactPosition.OLD_X, value);
        part.xOld = value;
    }

    @Redirect(method = "aiStep()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;yOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$yOld(EnderDragonPart part, double value) {
        ExactDoubles.write((ExactPosition) part, ExactPosition.OLD_Y, value);
        part.yOld = value;
    }

    @Redirect(method = "aiStep()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;zOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$zOld(EnderDragonPart part, double value) {
        ExactDoubles.write((ExactPosition) part, ExactPosition.OLD_Z, value);
        part.zOld = value;
    }
}
