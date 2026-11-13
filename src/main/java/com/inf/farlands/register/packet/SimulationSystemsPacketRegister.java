package com.inf.farlands.register.packet;

import com.inf.farlands.network.simulation.SimulationSystemsPacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

/**
 * 模拟层系统选择包的 type 登记。只从服务端发往客户端，因此只进 gameplay 表。
 */
public final class SimulationSystemsPacketRegister {

    private SimulationSystemsPacketRegister() {
    }

    public static void registerType() {
        Commonbounds.registerGameplay(new TypeAndCodec<>(
                SimulationSystemsPacket.TYPE,
                SimulationSystemsPacket.STREAM_CODEC));
    }
}
