package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 旧矿车行为：读 {@code minecart.xo/zo} 的字段指令改读槽位，朝向用的两个差量改帧内差值。
 *
 * <p>
 * 两个差量是 {@code minecart.xo - this.getX()}，即旧减新，与 {@code farlands$frameDelta} 的方向相反，所以要取负。
 *
 * <p>
 * {@code this} 是 behavior 不是矿车，而 {@code minecart} 字段声明在父类上，{@code @Shadow} 解析不到。矿车引用
 * 由上面两条字段重定向顺带捕获：它们的接收者形参就是矿车，字段读发生在 STORE 之前。槽位 5 与 7 取自该方法
 * 的局部变量表。
 */
@Mixin(OldMinecartBehavior.class)
public abstract class OldMinecartBehaviorPositionMixin {

    @Unique
    private AbstractMinecart farlands$cart;

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$xo(AbstractMinecart cart) {
        this.farlands$cart = cart;
        return ExactDoubles.read((ExactPosition) cart, ExactPosition.OLD_XO, cart.xo);
    }

    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$zo(AbstractMinecart cart) {
        this.farlands$cart = cart;
        return ExactDoubles.read((ExactPosition) cart, ExactPosition.OLD_ZO, cart.zo);
    }

    @ModifyVariable(method = "tick()V", at = @At("STORE"), index = 5)
    private double farlands$xDiff(double value) {
        AbstractMinecart cart = this.farlands$cart;
        return cart == null ? value : -((ExactPosition) cart).farlands$frameDelta(ExactPosition.POS_X);
    }

    @ModifyVariable(method = "tick()V", at = @At("STORE"), index = 7)
    private double farlands$zDiff(double value) {
        AbstractMinecart cart = this.farlands$cart;
        return cart == null ? value : -((ExactPosition) cart).farlands$frameDelta(ExactPosition.POS_Z);
    }
}
