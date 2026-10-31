package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.item.FallingBlockEntity;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 下落实体：构造器里写 {@code xo/yo/zo} 三条，{@code tick} 里读三条。
 *
 * <p>
 * 构造器里的注入点在 {@code super} 之后，此时实体的槽位尚未建，{@code ExactDoubles.write} 会按需建数组。
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityPositionMixin {

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/level/block/state/BlockState;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;xo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putXo(FallingBlockEntity self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_XO, value);
        self.xo = value;
    }

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/level/block/state/BlockState;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;yo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putYo(FallingBlockEntity self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_YO, value);
        self.yo = value;
    }

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/level/block/state/BlockState;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;zo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putZo(FallingBlockEntity self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_ZO, value);
        self.zo = value;
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$getXo(FallingBlockEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$getYo(FallingBlockEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_YO, self.yo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$getZo(FallingBlockEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }
}
