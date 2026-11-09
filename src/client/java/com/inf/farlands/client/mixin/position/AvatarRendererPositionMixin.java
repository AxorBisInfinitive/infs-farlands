package com.inf.farlands.client.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 玩家披风状态抽取时读 {@code avatar.xo/yo/zo} 的三条指令。
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererPositionMixin {

    @Redirect(method = "extractCapeState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Avatar;xo:D", opcode = Opcodes.GETFIELD))
    private double farlands$xo(Avatar avatar) {
        return ExactDoubles.read((ExactPosition) avatar, ExactPosition.OLD_XO, avatar.xo);
    }

    @Redirect(method = "extractCapeState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Avatar;yo:D", opcode = Opcodes.GETFIELD))
    private double farlands$yo(Avatar avatar) {
        return ExactDoubles.read((ExactPosition) avatar, ExactPosition.OLD_YO, avatar.yo);
    }

    @Redirect(method = "extractCapeState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Avatar;zo:D", opcode = Opcodes.GETFIELD))
    private double farlands$zo(Avatar avatar) {
        return ExactDoubles.read((ExactPosition) avatar, ExactPosition.OLD_ZO, avatar.zo);
    }
}
