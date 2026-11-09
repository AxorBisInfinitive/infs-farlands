package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.BodyRotationControl;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 身体转向控制里读 {@code mob.xo/zo} 的字段指令改读槽位，且 {@code isMoving} 的两个差量改成帧内差值。
 *
 * <p>
 * 字段的所属类是 {@code Mob}：接收者 {@code this.mob} 的静态类型就是它。
 *
 * <p>
 * 两个差量是 {@code mob.getX() - mob.xo} 这类两个投影相减，{@code 5e18} 处只能取 0 或 1024。这里改成
 * {@code @ModifyVariable}，不覆写 {@code isMoving}：覆写会让上面两条字段重定向零命中。
 *
 * <p>
 * {@code xd} 用槽位 1 定位，取自局部变量表。{@code zd} 在该表的登记里缺失，只有字节码的 {@code dstore_3}，
 * 所以用同类型第 1 个 STORE 定位。
 */
@Mixin(BodyRotationControl.class)
public abstract class BodyRotationControlPositionMixin {

    @Shadow
    private Mob mob;

    @ModifyVariable(method = "isMoving()Z", at = @At("STORE"), index = 1)
    private double farlands$deltaX(double value) {
        return ((ExactPosition) this.mob).farlands$frameDelta(ExactPosition.POS_X);
    }

    @ModifyVariable(method = "isMoving()Z", at = @At("STORE"), ordinal = 1)
    private double farlands$deltaZ(double value) {
        return ((ExactPosition) this.mob).farlands$frameDelta(ExactPosition.POS_Z);
    }

    @Redirect(method = "isMoving()Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Mob;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$xo(Mob mob) {
        return ExactDoubles.read((ExactPosition) mob, ExactPosition.OLD_XO, mob.xo);
    }

    @Redirect(method = "isMoving()Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Mob;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$zo(Mob mob) {
        return ExactDoubles.read((ExactPosition) mob, ExactPosition.OLD_ZO, mob.zo);
    }
}
