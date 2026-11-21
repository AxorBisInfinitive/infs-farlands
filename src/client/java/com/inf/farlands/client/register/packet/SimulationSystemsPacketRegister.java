package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.client.network.ClientSimulationSystems;
import com.inf.farlands.network.simulation.SimulationSystemsPacket;
import com.inf.farlands.simulation.model.SimulationData;

public class SimulationSystemsPacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                SimulationSystemsPacket.TYPE,
                (payload, context) -> ClientSimulationSystems.put(payload.dimension().identifier(),
                        new SimulationData.LevelSelection(payload.terrain(), payload.biome())));
    }
}
