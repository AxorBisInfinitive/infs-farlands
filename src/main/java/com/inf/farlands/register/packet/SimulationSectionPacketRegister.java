package com.inf.farlands.register.packet;

import com.inf.farlands.network.simulation.SimulationSectionPacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

/**
 * 模拟段包的 type 登记。只从服务端发往客户端，走 {@code Commonbounds} 让两端都在 codec 表里认得它。
 */
public final class SimulationSectionPacketRegister {

    private SimulationSectionPacketRegister() {
    }

    public static void registerType() {
        Commonbounds.register(new TypeAndCodec<>(SimulationSectionPacket.TYPE, SimulationSectionPacket.STREAM_CODEC));
    }
}
