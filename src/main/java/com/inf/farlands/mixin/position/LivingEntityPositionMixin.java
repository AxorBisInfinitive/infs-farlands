package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LivingEntity 里读旧位置的字段指令改读 256 位槽位；步幅距离改在帧里相减。
 *
 * <p>
 * {@code calculateEntityAnimation} 整体覆写：原式是两个世界量级的 double 相减，{@code 5e18} 处 ulp 是 1024，
 * 差值只能是 0 或 1024，手臂摆动与步幅因此全错。帧内相减后，量级由 long 承担、精度由小数承担。锚点为零时
 * 两种写法逐位相同。
 *
 * <p>
 * 覆写之后原方法体里的三条 {@code xo/yo/zo} 读指令不存在了，所以本类不再对 {@code calculateEntityAnimation}
 * 挂 {@code @Redirect}，否则那三条会零命中并在 {@code defaultRequire = 1} 下抛错。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPositionMixin {

    @Shadow
    @Final
    public WalkAnimationState walkAnimation;

    @Shadow
    protected abstract void updateWalkAnimation(float distance);

    @Overwrite
    public void calculateEntityAnimation(boolean useY) {
        LivingEntity self = (LivingEntity) (Object) this;
        ExactPosition holder = (ExactPosition) (Object) this;
        double deltaX = holder.farlands$frameDelta(ExactPosition.POS_X);
        double deltaY = holder.farlands$frameDelta(ExactPosition.POS_Y);
        double deltaZ = holder.farlands$frameDelta(ExactPosition.POS_Z);
        float distance = (float) Mth.length(deltaX, useY ? deltaY : 0.0, deltaZ);
        if (!self.isPassenger() && self.isAlive()) {
            this.updateWalkAnimation(distance);
        } else {
            this.walkAnimation.stop();
        }
    }

    @Redirect(method = "handleEntityEvent(B)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eventX(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "handleEntityEvent(B)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eventY(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_YO, self.yo);
    }

    @Redirect(method = "handleEntityEvent(B)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eventZ(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$tickX(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_XO, self.xo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$tickZ(LivingEntity self) {
        return ExactDoubles.read((ExactPosition) self, ExactPosition.OLD_ZO, self.zo);
    }
}
