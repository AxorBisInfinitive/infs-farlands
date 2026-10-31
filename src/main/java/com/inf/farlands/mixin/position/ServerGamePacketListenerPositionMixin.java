package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactDoubles;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 服务端每 tick 的玩家位置快照：{@code tickPlayer} 里写 {@code player.xo/yo/zo} 三条。
 *
 * <p>
 * 字段访问的所属类是 {@code ServerPlayer}，不是 {@code Entity}：字节码取接收者 {@code this.player} 的静态类型。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerPositionMixin {

    @Redirect(method = "tickPlayer()Z", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayer;xo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putXo(ServerPlayer player, double value) {
        ExactDoubles.write((ExactPosition) player, ExactPosition.OLD_XO, value);
        player.xo = value;
    }

    @Redirect(method = "tickPlayer()Z", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayer;yo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putYo(ServerPlayer player, double value) {
        ExactDoubles.write((ExactPosition) player, ExactPosition.OLD_YO, value);
        player.yo = value;
    }

    @Redirect(method = "tickPlayer()Z", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayer;zo:D", opcode = Opcodes.PUTFIELD))
    private void farlands$putZo(ServerPlayer player, double value) {
        ExactDoubles.write((ExactPosition) player, ExactPosition.OLD_ZO, value);
        player.zo = value;
    }
}
