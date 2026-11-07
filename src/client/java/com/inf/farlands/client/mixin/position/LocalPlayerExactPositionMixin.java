package com.inf.farlands.client.mixin.position;

import com.inf.farlands.network.position.ExactClientPositionPacket;
import com.inf.farlands.util.pos.ExactPosition;

import com.inf.long2.binaries.Binary256;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每次上报位置之后补发一条精确位置。
 *
 * <p>
 * 原版移动包带的是 {@code Vec3} 的三个 double，{@code 2^60} 处步长 256 格，服务端因此只知道 256 的整数倍。
 * 模拟层的段锚点按玩家 Y 取，那个量化会让层集整批跳变；这条包把本地的 256 位真值补过去。
 *
 * <p>
 * 挂在 {@code sendPosition} 的返回处：它是本端发移动包的唯一出口，返回值时位置与槽位都已就绪。比对上次发出的
 * 三轴，未变就不发，所以只转向或只发状态的那些 tick 不产生流量。
 *
 * <p>
 * 实例字段而不是静态：换关卡会换一个新的 {@code LocalPlayer}，缓存随之作废。
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerExactPositionMixin {

    @Unique
    private Binary256 farlands$lastExactX;

    @Unique
    private Binary256 farlands$lastExactY;

    @Unique
    private Binary256 farlands$lastExactZ;

    @Inject(method = "sendPosition()V", at = @At("RETURN"))
    private void farlands$sendExactPosition(CallbackInfo ci) {
        ExactPosition holder = (ExactPosition) (Object) this;
        Binary256 x = holder.farlands$exactValue(ExactPosition.POS_X);
        Binary256 y = holder.farlands$exactValue(ExactPosition.POS_Y);
        Binary256 z = holder.farlands$exactValue(ExactPosition.POS_Z);
        if (x.equals(this.farlands$lastExactX) && y.equals(this.farlands$lastExactY)
                && z.equals(this.farlands$lastExactZ)) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        this.farlands$lastExactX = x;
        this.farlands$lastExactY = y;
        this.farlands$lastExactZ = z;
        connection.send(new ServerboundCustomPayloadPacket(ExactClientPositionPacket.of(x, y, z)));
    }
}
