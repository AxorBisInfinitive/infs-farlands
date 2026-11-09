package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.item.ItemEntity;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 掉落物 {@code tick} 里同一字段既有写又有读，六条注入按 opcode 分开：写点回写字段并记槽位，读点取槽位。
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPositionMixin {

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/ItemEntity;xo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putXo(ItemEntity self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_XO, value);
        self.xo = value;
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/ItemEntity;yo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putYo(ItemEntity self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_YO, value);
        self.yo = value;
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/ItemEntity;zo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putZo(ItemEntity self, double value) {
        ExactDoubles.write((ExactPosition) self, ExactPosition.OLD_ZO, value);
        self.zo = value;
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/ItemEntity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$getXo(ItemEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/ItemEntity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$getYo(ItemEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_YO, self.yo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/item/ItemEntity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$getZo(ItemEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }

    /**
     * 合并检查的节流判据从「两个投影的格子号是否不同」改成帧内跨格判定。
     *
     * <p>
     * 原式是三次 `Mth.floor(xo) != Mth.floor(getX())`，两端都是量化过的 double，`5e18` 处多半相等，
     * 于是掉落物的合并总是按 40 tick 的慢档跑。`moved` 是该方法槽位 2 的 boolean。
     */
    @ModifyVariable(method = "tick()V", at = @At("STORE"), index = 2)
    private boolean farlands$moved(boolean value) {
        ExactPosition holder = (ExactPosition) (Object) this;
        return holder.farlands$crossedCell(ExactPosition.POS_X)
                || holder.farlands$crossedCell(ExactPosition.POS_Y)
                || holder.farlands$crossedCell(ExactPosition.POS_Z);
    }
}
