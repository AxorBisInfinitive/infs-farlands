package com.inf.farlands.client.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 实体渲染抽取阶段读旧位置的六条指令：三条是 {@code Entity} 的，三条是矿车的。
 *
 * <p>
 * 矿车那三条的所属类是 {@code AbstractMinecart}，因为字节码取的是强转后接收者的静态类型。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererPositionMixin {

    @Redirect(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;xOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$entityXOld(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_X, entity.xOld);
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;yOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$entityYOld(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_Y, entity.yOld);
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;zOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$entityZOld(Entity entity) {
        return ExactDoubles.read((ExactPosition) entity, ExactPosition.OLD_Z, entity.zOld);
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;xOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$cartXOld(AbstractMinecart cart) {
        return ExactDoubles.read((ExactPosition) cart, ExactPosition.OLD_X, cart.xOld);
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;yOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$cartYOld(AbstractMinecart cart) {
        return ExactDoubles.read((ExactPosition) cart, ExactPosition.OLD_Y, cart.yOld);
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;zOld:D", opcode = Opcodes.GETFIELD))
    private double farlands$cartZOld(AbstractMinecart cart) {
        return ExactDoubles.read((ExactPosition) cart, ExactPosition.OLD_Z, cart.zOld);
    }
}
