package com.inf.farlands.network.expand.y;

import com.inf.farlands.InfsFarlands;

import java.util.Arrays;
import java.util.List;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
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

    /** 光照载荷的压缩器与解压器，类加载时取一次，之后只读。 */
    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();

    private static final LZ4Compressor COMPRESSOR = LZ4.fastCompressor();

    private static final LZ4FastDecompressor DECOMPRESSOR = LZ4.fastDecompressor();

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

    /**
     * 线上光照编码，三档：null 表示这一层不存在；1 字节表示整层同值；其余为 2,048 字节的载荷。
     *
     * <p>
     * 载荷有两种：首字节 {@code 0xFF} 是原始 2,048 字节，首字节 {@code 0xFE} 是 lz4 压缩块。压缩只在这个长度
     * 严格小于原始档时才用，所以原样发送永远是上界。取哪个由压缩结果决定，不另外声明。
     *
     * <p>
     * 同值判定按 nibble 而不是按字节：一个字节装两格，{@code 0x0F} 这种高低 nibble 不同的字节全等也仍然不是
     * 同值层。判定通过后直接给 1 字节，省掉后面整段压缩。
     */
    public static byte[] encodeLight(DataLayer layer) {
        if (layer == null) {
            return null;
        }
        if (layer.isDefinitelyHomogenous()) {
            return new byte[] { (byte) layer.get(0, 0, 0) };
        }
        byte[] data = layer.getData();
        int uniform = uniformValue(data);
        if (uniform >= 0) {
            return new byte[] { (byte) uniform };
        }
        byte[] scratch = new byte[COMPRESSOR.maxCompressedLength(2048) + 1];
        scratch[0] = (byte) 0xFE;
        int compressed = COMPRESSOR.compress(data, 0, 2048, scratch, 1, scratch.length - 1);
        if (compressed + 1 < 2049) {
            return Arrays.copyOf(scratch, compressed + 1);
        }
        byte[] out = new byte[2049];
        out[0] = (byte) 0xFF;
        System.arraycopy(data, 0, out, 1, 2048);
        return out;
    }

    /**
     * 该层若整层同值，返回那个 nibble 值，否则 -1。
     *
     * <p>
     * 先取首字节的低 nibble 当候选，再要求首字节正好等于 {@code v | v << 4}，然后逐字节比。这个判据同时覆盖
     * 「高低 nibble 不同」的脏数组。
     */
    private static int uniformValue(byte[] data) {
        int value = data[0] & 15;
        byte expect = (byte) (value | value << 4);
        if (data[0] != expect) {
            return -1;
        }
        for (int i = 1; i < 2048; i++) {
            if (data[i] != expect) {
                return -1;
            }
        }
        return value;
    }

    /**
     * 把整层同值的已分配层折成未分配层。
     *
     * <p>
     * {@code isDefinitelyHomogenous} 只看 {@code data == null}，而经 {@link #decodeLight} 或读盘构造出来的
     * 同值层永远带着数组，于是每层要占满 2,049 字节。折叠之后该判据为真，编码回到 1 字节，且
     * {@code get}、{@code isEmpty}、{@code getData} 三处与折叠前逐项同值。
     */
    public static DataLayer foldUniform(DataLayer layer) {
        if (layer == null || layer.isDefinitelyHomogenous()) {
            return layer;
        }
        int uniform = uniformValue(layer.getData());
        return uniform < 0 ? layer : new DataLayer(uniform);
    }

    /**
     * 取该层的 2,048 字节原始数据，且不改变层自己的状态。
     *
     * <p>
     * {@code DataLayer.getData} 在未分配时会分配数组并回写字段，同值层一旦被它碰过就不再同值，落盘路径因此
     * 会把折叠状态抹掉。这里改为本地新建一份填满 {@code v | v << 4} 的数组，与原数组逐字节相同。
     */
    public static byte[] rawLightBytes(DataLayer layer) {
        if (!layer.isDefinitelyHomogenous()) {
            return layer.getData();
        }
        int value = layer.get(0, 0, 0) & 15;
        byte[] data = new byte[2048];
        Arrays.fill(data, (byte) (value | value << 4));
        return data;
    }

    /** 一层在包里的字节数：1 字节的布尔加长度前缀与载荷。null 只算那个布尔。 */
    public static int encodedLightSize(byte[] enc) {
        return enc == null ? 1 : 1 + VarInt.getByteSize(enc.length) + enc.length;
    }

    /** 一条条目在包里的字节数，与 {@link #writeTo} 逐字段对应，供发送预算用。 */
    public static int encodedSectionSize(SectionEntry entry) {
        int size = 8 + VarInt.getByteSize(entry.sectionY())
                + VarInt.getByteSize(entry.sectionData().length) + entry.sectionData().length;
        size += encodedLightSize(entry.blockLight());
        size += encodedLightSize(entry.skyLight());
        return size;
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
        if (enc[0] == (byte) 0xFE) {
            DECOMPRESSOR.decompress(enc, 1, data, 0, 2048);
            return new DataLayer(data);
        }
        if (enc.length != 2049 || enc[0] != (byte) 0xFF) {
            throw new IllegalArgumentException("farlands: malformed light payload, length " + enc.length
                    + " marker " + (enc.length > 0 ? enc[0] : 0));
        }
        System.arraycopy(enc, 1, data, 0, 2048);
        return new DataLayer(data);
    }

    @Override
    public Type<ChunkDataPacket> type() {
        return TYPE;
    }
}
