package com.inf.farlands.mixin.fix.xyz;

import com.inf.farlands.network.position.ExactPositionPacket;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.long2.binaries.Binary256;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体同步之后补发一条精确位置。
 *
 * <p>
 * 实体在极端坐标上恒走 {@code ClientboundEntityPositionSyncPacket}，{@code deltaTooBig} 恒真，那条包的位置
 * 也是 double。这里在 {@code sendChanges} 的末尾、vanilla 的包已经发出之后补一条 payload。
 *
 * <p>
 * 必须门控：{@code sendChanges} 每 tick 对每个被追踪实体跑一次，远处实体每 tick 都发一次同步包，无条件补发就是
 * 每实体每 tick 一百九十二字节。标记只在精确写入之后置起，正常移动不置。
 */
@Mixin(ServerEntity.class)
public class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private ServerEntity.Synchronizer synchronizer;

    @Inject(method = "sendChanges()V", at = @At("RETURN"))
    private void farlands$sendExact(CallbackInfo ci) {
        if (!(this.entity instanceof ExactPosition holder) || !holder.farlands$takeExactSync()) {
            return;
        }
        Binary256[] slots = holder.farlands$exactSlots();
        this.synchronizer.sendToTrackingPlayers(new ClientboundCustomPayloadPacket(new ExactPositionPacket(
                this.entity.getId(),
                ExactPositionPacket.pack(slots[ExactPosition.POS_X], slots[ExactPosition.POS_Y],
                        slots[ExactPosition.POS_Z]),
                ExactPositionPacket.pack(slots[ExactPosition.OLD_XO], slots[ExactPosition.OLD_YO],
                        slots[ExactPosition.OLD_ZO]))));
    }
}
