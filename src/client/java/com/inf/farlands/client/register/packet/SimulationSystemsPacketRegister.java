package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.client.network.ClientSimulationSystems;
import com.inf.farlands.network.simulation.SimulationSystemsPacket;

public class SimulationSystemsPacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                SimulationSystemsPacket.TYPE,
                (payload, context) -> ClientSimulationSystems.put(payload.dimension().identifier(),
                        payload.selection()));
    }
}
