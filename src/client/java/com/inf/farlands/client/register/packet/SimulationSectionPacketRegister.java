package com.inf.farlands.client.register.packet;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.network.simulation.SimulationSectionPacket;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import com.inf.long2.ints.Int1024;

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
 *
 * <p>
 * 线上一段坐标是 128 字节，客户端段表仍是 long 段号。两个宽度的交界收在本类：超出 long 的段在这里丢弃，并只告警
 * 一次，不在下层静默截断。客户端段表拓宽之后这一层即可删掉。
 */
public final class SimulationSectionPacketRegister {

    /** 超宽告警只打一次，避免每包一次。 */
    private static boolean farlands$narrowWarned;

    private SimulationSectionPacketRegister() {
    }

    /** 该轴段号能否落进 long，即区间 {@code [-2^63, 2^63-1]}。 */
    private static boolean farlands$fitsLong(Int1024 value) {
        return value.bitLength() <= 63;
    }

    private static void farlands$warnNarrowed() {
        if (farlands$narrowWarned) {
            return;
        }
        farlands$narrowWarned = true;
        InfsFarlands.LOGGER.warn("farlands: simulation section beyond long dropped on client, "
                + "client segment table stays long-wide until it is widened");
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
                if (!farlands$fitsLong(entry.x()) || !farlands$fitsLong(entry.y())
                        || !farlands$fitsLong(entry.z())) {
                    farlands$warnNarrowed();
                    continue;
                }
                LevelChunkSection container = new LevelChunkSection(level.palettedContainerFactory());
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(entry.data()));
                try {
                    container.read(buffer);
                } finally {
                    buffer.release();
                }
                DataLayer blockLight = ChunkDataPacket.decodeLight(entry.blockLight());
                DataLayer skyLight = ChunkDataPacket.decodeLight(entry.skyLight());
                simulation.applySection(entry.x().toLong(), entry.y().toLong(), entry.z().toLong(), container,
                        entry.shapeMode(), blockLight, skyLight);
            }
            for (SimulationSectionKey key : payload.removed()) {
                if (!farlands$fitsLong(key.x()) || !farlands$fitsLong(key.y()) || !farlands$fitsLong(key.z())) {
                    continue;
                }
                simulation.dropSection(key.x().toLong(), key.y().toLong(), key.z().toLong());
            }
        });
    }
}
