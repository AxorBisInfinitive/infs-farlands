package com.inf.farlands.network.expand.y;

import com.inf.farlands.InfsFarlands;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * 窗口滑动 section 包。
 */
public record ChunkDataPacket(
        ResourceKey<Level> dimension,
        int windowMinY,
        List<SectionEntry> sections) implements CustomPacketPayload {

    public record SectionEntry(
            int chunkX,
            int chunkZ,
            int sectionY,
            byte[] sectionData,
            byte[] blockLight,
            byte[] skyLight) {
    }

    public static final Type<ChunkDataPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "chunk_data"));

    public static final StreamCodec<FriendlyByteBuf, ChunkDataPacket> STREAM_CODEC = StreamCodec.of(
            ChunkDataPacket::writeTo,
            ChunkDataPacket::readFrom);

    public static void writeTo(FriendlyByteBuf buffer, ChunkDataPacket pkt) {
        buffer.writeResourceKey(pkt.dimension());
        buffer.writeInt(pkt.windowMinY());
        buffer.writeVarInt(pkt.sections().size());
        for (SectionEntry e : pkt.sections()) {
            buffer.writeInt(e.chunkX());
            buffer.writeInt(e.chunkZ());
            buffer.writeVarInt(e.sectionY());
            buffer.writeByteArray(e.sectionData());
            writeLight(buffer, e.blockLight());
            writeLight(buffer, e.skyLight());
        }
    }

    private static void writeLight(FriendlyByteBuf buffer, byte[] light) {
        buffer.writeBoolean(light != null);
        if (light != null) {
            buffer.writeByteArray(light);
        }
    }

    public static ChunkDataPacket readFrom(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
        int windowMinY = buffer.readInt();
        int count = buffer.readVarInt();
        List<SectionEntry> entries = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int cx = buffer.readInt();
            int cz = buffer.readInt();
            int sy = buffer.readVarInt();
            byte[] sectionData = buffer.readByteArray();
            byte[] blockLight = readLight(buffer);
            byte[] skyLight = readLight(buffer);
            entries.add(new SectionEntry(cx, cz, sy, sectionData, blockLight, skyLight));
        }
        return new ChunkDataPacket(dimension, windowMinY, entries);
    }

    private static byte[] readLight(FriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return null;
        }
        return buffer.readByteArray();
    }

    // ---- 服务端发送编码 ----

    /** null → null；均匀 → [v]；非均匀 → [0xFF, 2048 bytes] */
    public static byte[] encodeLight(DataLayer layer) {
        if (layer == null) {
            return null;
        }
        if (layer.isDefinitelyHomogenous()) {
            return new byte[] { (byte) layer.get(0, 0, 0) };
        }
        byte[] data = layer.getData();
        byte[] out = new byte[2049];
        out[0] = (byte) 0xFF;
        System.arraycopy(data, 0, out, 1, 2048);
        return out;
    }

    // ---- 客户端 handler 解码 ----

    public static DataLayer decodeLight(byte[] enc) {
        if (enc == null) {
            return null;
        }
        if (enc.length == 1) {
            return new DataLayer(enc[0] & 255);
        }
        byte[] data = new byte[2048];
        System.arraycopy(enc, 1, data, 0, 2048);
        return new DataLayer(data);
    }

    @Override
    public Type<ChunkDataPacket> type() {
        return TYPE;
    }
}
