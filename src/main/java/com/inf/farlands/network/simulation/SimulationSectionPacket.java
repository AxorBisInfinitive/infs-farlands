package com.inf.farlands.network.simulation;

import java.util.ArrayList;
import java.util.List;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 模拟段包：装载若干段，卸载若干段。
 *
 * <p>
 * 一段的块与群系直接沿用原版的段容器线格式，写进 {@code data}；光照沿用 section 包那一套一字节或 2049 字节的
 * 编码。坐标三轴用 long，因为段号可以超出 int。
 *
 * <p>
 * 装载与卸载放在一条包里：同一 tick 里进出视距的段常常同时发生，拆两条会让同一玩家的包数翻倍。
 */
public record SimulationSectionPacket(
        ResourceKey<Level> dimension,
        List<SectionEntry> loaded,
        List<SimulationSectionKey> removed) implements CustomPacketPayload {

    /** 一段的线上表示。{@code data} 是段容器的原始字节，{@code shapeMode} 见 {@link SimulationSectionData}。 */
    public record SectionEntry(long x, long y, long z, int shapeMode, byte[] data, byte[] blockLight, byte[] skyLight) {
    }

    public static final Type<SimulationSectionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "simulation_section"));

    public static final StreamCodec<FriendlyByteBuf, SimulationSectionPacket> STREAM_CODEC = StreamCodec.of(
            SimulationSectionPacket::writeTo,
            SimulationSectionPacket::readFrom);

    /**
     * 服务端编码一段。容器写到临时缓冲再取字节，与 section 包的段体同一套做法。
     *
     * <p>
     * 只在服务端主线程调：容器是服务端那份，与客户端的同名对象不是一回事。
     */
    public static SectionEntry encode(SimulationSectionKey key, SimulationSectionData data) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            data.section.write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new SectionEntry(key.x(), key.y(), key.z(), data.shapeMode(), bytes,
                    ChunkDataPacket.encodeLight(data.blockLight),
                    ChunkDataPacket.encodeLight(data.skyLight));
        } finally {
            buffer.release();
        }
    }

    /**
     * 一条已编码条目在包里的字节数，供发送预算用。
     *
     * <p>
     * 取实际字节而不是按层是否同质去估：光照载荷可能是 1 字节、原始 2,048 字节或压缩块，估的公式一旦与编码
     * 分叉，预算就会按大于实发的字节记账，优化发不出去。
     */
    public static int encodedSize(SectionEntry entry) {
        int size = 24 + 1 + VarInt.getByteSize(entry.data().length) + entry.data().length;
        size += ChunkDataPacket.encodedLightSize(entry.blockLight());
        size += ChunkDataPacket.encodedLightSize(entry.skyLight());
        return size;
    }

    public static void writeTo(FriendlyByteBuf buffer, SimulationSectionPacket pkt) {
        buffer.writeResourceKey(pkt.dimension());
        buffer.writeVarInt(pkt.loaded().size());
        for (SectionEntry e : pkt.loaded()) {
            buffer.writeLong(e.x());
            buffer.writeLong(e.y());
            buffer.writeLong(e.z());
            buffer.writeByte(e.shapeMode());
            buffer.writeByteArray(e.data());
            writeLight(buffer, e.blockLight());
            writeLight(buffer, e.skyLight());
        }
        buffer.writeVarInt(pkt.removed().size());
        for (SimulationSectionKey key : pkt.removed()) {
            buffer.writeLong(key.x());
            buffer.writeLong(key.y());
            buffer.writeLong(key.z());
        }
    }

    public static SimulationSectionPacket readFrom(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
        int count = buffer.readVarInt();
        List<SectionEntry> loaded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long x = buffer.readLong();
            long y = buffer.readLong();
            long z = buffer.readLong();
            int shapeMode = buffer.readByte();
            byte[] data = buffer.readByteArray();
            byte[] blockLight = readLight(buffer);
            byte[] skyLight = readLight(buffer);
            loaded.add(new SectionEntry(x, y, z, shapeMode, data, blockLight, skyLight));
        }
        int removedCount = buffer.readVarInt();
        List<SimulationSectionKey> removed = new ArrayList<>(removedCount);
        for (int i = 0; i < removedCount; i++) {
            removed.add(new SimulationSectionKey(buffer.readLong(), buffer.readLong(), buffer.readLong()));
        }
        return new SimulationSectionPacket(dimension, loaded, removed);
    }

    private static void writeLight(FriendlyByteBuf buffer, byte[] light) {
        buffer.writeBoolean(light != null);
        if (light != null) {
            buffer.writeByteArray(light);
        }
    }

    private static byte[] readLight(FriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return null;
        }
        return buffer.readByteArray();
    }

    @Override
    public Type<SimulationSectionPacket> type() {
        return TYPE;
    }
}
