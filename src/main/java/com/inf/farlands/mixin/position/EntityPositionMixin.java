package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.long2.binaries.Binary256;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体位置的内部表示改成 256 位，出口仍是 double。
 *
 * <p>
 * 捕获点取 {@code setPosRaw} 的头部，它是位置唯一的写入口：正文里 {@code this.position} 的赋值只有构造器的
 * {@code Vec3.ZERO} 与这个方法两处。取头部而不是覆写整个方法，是因为头部注入不复制那 26 行原体，也不顶掉别的
 * 注入器挂在这条指令上的机会。
 *
 * <p>
 * 三个无参 getter 改成读槽位，槽位为空时回落到 {@code position} 字段：构造器在第一次 {@code setPosRaw} 之前就
 * 写了位置，子类构造器也可能更早触发读取。回落值与槽位值逐位相同，因为槽位就是同一批 double 的 256 位表示。
 *
 * <p>
 * 方法声明带 {@code final}：覆写体替换的是方法节点，标志随 mixin 声明走，不写 {@code final} 会让
 * {@code Entity.getX} 从 final 变成非 final。
 *
 * <p>
 * {@code Binary256.of(double)} 对任何有限 double 无损，237 位有效数覆盖 53 位，指数域覆盖 double 全域；
 * 无穷与 NaN 走同名的常量。因此这一层在可观测行为上是恒等变换，它先把存储换掉，等有更宽的写入方时才有收益。
 */
@Mixin(Entity.class)
public abstract class EntityPositionMixin implements ExactPosition {

    @Shadow
    private Vec3 position;

    /**
     * 九槽 256 位存储。不写初始化器：{@code @Unique} 字段的初始化器可能被静默丢弃，见仓库既有约定。
     */
    @Unique
    private Binary256[] farlands$slots;

    @Override
    public Binary256[] farlands$exactSlots() {
        Binary256[] slots = this.farlands$slots;
        if (slots == null) {
            slots = new Binary256[9];
            this.farlands$slots = slots;
        }
        return slots;
    }

    /** 把这次的三个入参记进位置槽位。取头部，正文里那处早退不影响等价性。 */
    @Inject(method = "setPosRaw(DDD)V", at = @At("HEAD"))
    private void farlands$storePosition(double x, double y, double z, CallbackInfo ci) {
        Binary256[] slots = this.farlands$exactSlots();
        slots[POS_X] = Binary256.of(x);
        slots[POS_Y] = Binary256.of(y);
        slots[POS_Z] = Binary256.of(z);
    }

    @Overwrite
    public final double getX() {
        Binary256 exact = this.farlands$exactSlots()[POS_X];
        return exact != null ? exact.toDouble() : this.position.x;
    }

    @Overwrite
    public final double getY() {
        Binary256 exact = this.farlands$exactSlots()[POS_Y];
        return exact != null ? exact.toDouble() : this.position.y;
    }

    @Overwrite
    public final double getZ() {
        Binary256 exact = this.farlands$exactSlots()[POS_Z];
        return exact != null ? exact.toDouble() : this.position.z;
    }

    /**
     * 旧位置六轴的写点。{@code setOldPos} 与 {@code absSnapTo} 各只有一个入参来源，两条头部注入覆盖九个
     * {@code putfield}，不必逐字段 redirect。
     *
     * <p>
     * {@code absSnapTo} 的 X 与 Z 在方法体里过一道 {@code Mth.clamp}，而 {@code expand.xz.border.EntityMixin}
     * 已把这道钳制放行，入参即落值，两个来源同值。
     */
    @Inject(method = "setOldPos(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void farlands$storeOldPos(Vec3 pos, CallbackInfo ci) {
        ExactDoubles.write(this, OLD_XO, pos.x);
        ExactDoubles.write(this, OLD_X, pos.x);
        ExactDoubles.write(this, OLD_YO, pos.y);
        ExactDoubles.write(this, OLD_Y, pos.y);
        ExactDoubles.write(this, OLD_ZO, pos.z);
        ExactDoubles.write(this, OLD_Z, pos.z);
    }

    @Inject(method = "absSnapTo(DDD)V", at = @At("HEAD"))
    private void farlands$storeSnapped(double x, double y, double z, CallbackInfo ci) {
        ExactDoubles.write(this, OLD_XO, x);
        ExactDoubles.write(this, OLD_YO, y);
        ExactDoubles.write(this, OLD_ZO, z);
    }

    @Redirect(method = "oldPosition()Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$oldX(Entity self) {
        return ExactDoubles.read(this, OLD_X, self.xOld);
    }

    @Redirect(method = "oldPosition()Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$oldY(Entity self) {
        return ExactDoubles.read(this, OLD_Y, self.yOld);
    }

    @Redirect(method = "oldPosition()Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$oldZ(Entity self) {
        return ExactDoubles.read(this, OLD_Z, self.zOld);
    }

    @Redirect(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eyeX(Entity self) {
        return ExactDoubles.read(this, OLD_XO, self.xo);
    }

    @Redirect(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eyeY(Entity self) {
        return ExactDoubles.read(this, OLD_YO, self.yo);
    }

    @Redirect(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$eyeZ(Entity self) {
        return ExactDoubles.read(this, OLD_ZO, self.zo);
    }

    @Redirect(method = "getPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$lerpX(Entity self) {
        return ExactDoubles.read(this, OLD_XO, self.xo);
    }

    @Redirect(method = "getPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$lerpY(Entity self) {
        return ExactDoubles.read(this, OLD_YO, self.yo);
    }

    @Redirect(method = "getPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$lerpZ(Entity self) {
        return ExactDoubles.read(this, OLD_ZO, self.zo);
    }
}
