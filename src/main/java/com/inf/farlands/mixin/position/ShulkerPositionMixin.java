package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.monster.Shulker;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 潜影贝自己覆写的 {@code setPos} 里写 {@code xOld/yOld/zOld} 的三条指令。
 */
@Mixin(Shulker.class)
public abstract class ShulkerPositionMixin {

    @Redirect(method = "setPos(DDD)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/Shulker;xOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$xOld(Shulker self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_X, value);
        self.xOld = value;
    }

    @Redirect(method = "setPos(DDD)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/Shulker;yOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$yOld(Shulker self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_Y, value);
        self.yOld = value;
    }

    @Redirect(method = "setPos(DDD)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/Shulker;zOld:D", opcode = Opcodes.PUTFIELD))
    private void farlands$zOld(Shulker self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_Z, value);
        self.zOld = value;
    }
}
