package com.inf.farlands.register.packet;

import com.inf.farlands.network.position.ExactClientPositionPacket;
import com.inf.farlands.util.network.ServerPacketHandlers;
import com.inf.farlands.util.network.Serverbounds;
import com.inf.farlands.util.pos.ExactPosition;

import com.inf.long2.binaries.Binary256;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端精确位置上报的登记与处理。
 *
 * <p>
 * 处理体有三道门：玩家在、玩家实现 {@link ExactPosition}、以及上报值与服务端当前的 double 位置三轴各不差
 * 超过一格。第三道是必要的：被服务端拒掉的移动不能把精确值写进槽位，否则段锚点会跟着一个不成立的位置走。
 *
 * <p>
 * 只写位置三槽，旧位置仍由服务端自己的 {@code setOldPos} 维护。
 */
public final class ExactClientPositionPacketRegister {

    /** 上报值与服务端位置的允许偏差，单位格。 */
    private static final double TOLERANCE = 1.0;

    private ExactClientPositionPacketRegister() {
    }

    public static void registerType() {
        Serverbounds.registerGameplay(new TypeAndCodec<>(
                ExactClientPositionPacket.TYPE,
                ExactClientPositionPacket.STREAM_CODEC));
    }

    public static void registerHandler() {
        ServerPacketHandlers.register(ExactClientPositionPacket.TYPE, (payload, listener) -> {
            ServerPlayer player = listener.player;
            if (player == null || !(player instanceof ExactPosition holder)) {
                return;
            }
            Binary256[] values = payload.unpack();
            if (Math.abs(values[0].toDouble() - player.getX()) > TOLERANCE
                    || Math.abs(values[1].toDouble() - player.getY()) > TOLERANCE
                    || Math.abs(values[2].toDouble() - player.getZ()) > TOLERANCE) {
                return;
            }
            holder.farlands$applyExactPosition(values[0], values[1], values[2]);
        });
    }
}
