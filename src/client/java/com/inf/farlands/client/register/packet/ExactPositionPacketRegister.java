package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.network.position.ExactPositionPacket;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.long2.binaries.Binary256;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * 精确位置包的客户端接收链：按实体 id 找到实体，把九槽写成 payload 里的 256 位值。
 *
 * <p>
 * 只写槽位：位置、包围盒、方块与区块缓存、旧位置都已经由 vanilla 的位置包落好，这里补的是真值。所以本包必须
 * 排在 vanilla 包之后，服务端的发送点就落在 vanilla 发送之后。
 */
public final class ExactPositionPacketRegister {

    private ExactPositionPacketRegister() {
    }

    public static void registerHandler() {
        ClientPacketHandlers.register(ExactPositionPacket.TYPE, (payload, context) -> {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            Entity entity = level.getEntity(payload.entityId());
            if (!(entity instanceof ExactPosition holder)) {
                return;
            }
            Binary256[] position = ExactPositionPacket.unpack(payload.position());
            Binary256[] previous = ExactPositionPacket.unpack(payload.previous());
            holder.farlands$adoptExact(position[0], position[1], position[2],
                    previous[0], previous[1], previous[2]);
        });
    }
}
