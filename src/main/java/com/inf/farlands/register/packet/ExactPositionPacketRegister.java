package com.inf.farlands.register.packet;

import com.inf.farlands.network.position.ExactPositionPacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

/**
 * 精确位置包的 type 登记，只做一次。
 *
 * <p>
 * 走 {@code Commonbounds} 是因为这条包只从服务端发往客户端，两端都要在 codec 表里认得它。登记入口由
 * {@code FarlandsRegister.registerPayloadTypes} 统一调，那里已经处理了「第三方模组先碰 codec 静态块」的顺序问题。
 */
public final class ExactPositionPacketRegister {

    private ExactPositionPacketRegister() {
    }

    public static void registerType() {
        Commonbounds.register(new TypeAndCodec<>(ExactPositionPacket.TYPE, ExactPositionPacket.STREAM_CODEC));
    }
}
