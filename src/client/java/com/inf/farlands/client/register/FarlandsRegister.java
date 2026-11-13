package com.inf.farlands.client.register;

import com.inf.farlands.client.register.command.ClientFarLandsCommands;
import com.inf.farlands.client.register.packet.*;
import com.inf.farlands.command.FarlandsCommandRegistry;

public class FarlandsRegister {
    public static void registerStatic() {
        ClampTogglePacketRegister.registerType();
    }

    public static void register() {
        ChunkDataPacketRegister.registerHandler();
        ClampStatePacketRegister.registerHandler();
        ExactPositionPacketRegister.registerHandler();
        LightUpdatePacketRegister.registerHandler();
        SectionBlocksUpdatePacketRegister.registerHandler();
        SystemsPacketRegister.registerHandler();
        SimulationSystemsPacketRegister.registerHandler();
        SimulationSectionPacketRegister.registerHandler();
        // 客户端命令监听器只登记一次；服务端命令树每次到达时由 ClientPacketListenerMixin 触发。
        FarlandsCommandRegistry.registerClient(ClientFarLandsCommands::register);
    }
}
