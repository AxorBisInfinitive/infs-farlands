package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.network.simulation.SimulationSectionPacket;
import com.inf.farlands.simulation.SimulationSectionKey;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 模拟段包的客户端接收链：把每段的容器、光照与形状模式写进 {@link Simulation}，撤回的段直接删。
 *
 * <p>
 * 处理体在客户端主线程：`ClientPayloadDispatchMixin` 挂在同线程跳转之后，所以这里可以直接改段表、待烘集合与
 * 显存，不必再跳一次线程。
 */
public final class SimulationSectionPacketRegister {

    private SimulationSectionPacketRegister() {
    }

    public static void registerHandler() {
        ClientPacketHandlers.register(SimulationSectionPacket.TYPE, (payload, context) -> {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel level = minecraft.level;
            if (level == null) {
                return;
            }
            Simulation simulation = ((SimulationHolder) minecraft).simulation();
            for (SimulationSectionPacket.SectionEntry entry : payload.loaded()) {
                LevelChunkSection container = new LevelChunkSection(level.palettedContainerFactory());
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(entry.data()));
                try {
                    container.read(buffer);
                } finally {
                    buffer.release();
                }
                DataLayer blockLight = ChunkDataPacket.decodeLight(entry.blockLight());
                DataLayer skyLight = ChunkDataPacket.decodeLight(entry.skyLight());
                simulation.applySection(new SimulationSectionKey(entry.x(), entry.y(), entry.z()), container,
                        entry.shapeMode(), blockLight, skyLight);
            }
            for (SimulationSectionKey key : payload.removed()) {
                simulation.dropSection(key);
            }
        });
    }
}
