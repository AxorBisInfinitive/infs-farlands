package com.inf.farlands.mixin.fix.xyz;

import java.util.Set;

import com.inf.farlands.network.position.ExactPositionPacket;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.long2.binaries.Binary256;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家传送之后补发一条精确位置。
 *
 * <p>
 * 原版的位置包是 {@code PositionMoveRotation}，位置是三个 double，客户端拿到的就是舍入值。本模组把真值放在
 * 九槽里，这里在 vanilla 的包发出之后补一条 payload，客户端收到后只覆写槽位。
 *
 * <p>
 * 只在该实体有「待同步」标记时发。标记由 {@code teleportSetPosition} 的返回处置起，那里刚写完精确值。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class TeleportSendMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("RETURN"))
    private void farlands$sendExact(PositionMoveRotation destination, Set<Relative> relatives, CallbackInfo ci) {
        if (!(this.player instanceof ExactPosition holder) || !holder.farlands$takeExactSync()) {
            return;
        }
        Binary256[] slots = holder.farlands$exactSlots();
        this.player.connection.send(new ClientboundCustomPayloadPacket(new ExactPositionPacket(
                this.player.getId(),
                ExactPositionPacket.pack(slots[ExactPosition.POS_X], slots[ExactPosition.POS_Y],
                        slots[ExactPosition.POS_Z]),
                ExactPositionPacket.pack(slots[ExactPosition.OLD_XO], slots[ExactPosition.OLD_YO],
                        slots[ExactPosition.OLD_ZO]))));
    }
}
