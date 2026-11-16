package com.inf.farlands.network.position;

import com.inf.farlands.InfsFarlands;

import com.inf.long2.binaries.binary256.Binary256;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 一个实体的精确位置：当前三轴与旧位置三轴，各按 256 位原样带上。
 *
 * <p>
 * 原版的位置包装的是 {@code PositionMoveRotation}，位置是 {@code Vec3}，三个 double，而且那是 record 分量，
 * 无法扩展。所以精确值走这条并行 payload：vanilla 的包先把位置、包围盒、缓存与旧位置都落好，本包后到，只覆写
 * 槽位。
 *
 * <p>
 * 每轴四个 long，是 {@code Binary256.toBytes} 的 32 字节按 8 字节大端依次切出的四段，反向用
 * {@code of(byte[])}。这两个入口与旧版的 {@code getRawLong} 与 {@code fromRawBits} 逐位同值，所以线上的十二个
 * long 与存档的二十四个 int 都不变。
 *
 * <p>
 * 旧位置也带，是因为客户端根本不从服务端收旧位置：它用自己上一帧的旧位置与包里的 change 现算，那个算式在
 * double 上做，插值起点因此同样会偏。
 */
public record ExactPositionPacket(int entityId, long[] position, long[] previous) implements CustomPacketPayload {

    /** 三轴各四个 long。 */
    public static final int AXIS_LONGS = 12;

    public static final Type<ExactPositionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "exact_position"));

    public static final StreamCodec<FriendlyByteBuf, ExactPositionPacket> STREAM_CODEC = StreamCodec.of(
            ExactPositionPacket::writeTo,
            ExactPositionPacket::readFrom);

    /** 三轴打包成十二个 long。 */
    public static long[] pack(Binary256 x, Binary256 y, Binary256 z) {
        long[] raw = new long[AXIS_LONGS];
        farlands$put(raw, 0, x);
        farlands$put(raw, 4, y);
        farlands$put(raw, 8, z);
        return raw;
    }

    /** 十二个 long 还原成三轴。 */
    public static Binary256[] unpack(long[] raw) {
        return new Binary256[] {
                farlands$get(raw, 0),
                farlands$get(raw, 4),
                farlands$get(raw, 8)
        };
    }

    /** 一个 256 位值切成四个 long：先取 32 字节大端编码，再按 8 字节一组拼回。 */
    private static void farlands$put(long[] raw, int offset, Binary256 value) {
        byte[] bytes = value.toBytes();
        for (int i = 0; i < 4; i++) {
            long word = 0L;
            for (int b = 0; b < 8; b++) {
                word = (word << 8) | (bytes[i * 8 + b] & 0xFFL);
            }
            raw[offset + i] = word;
        }
    }

    /** 四个 long 拼回一个 256 位值：摊成 32 字节大端，再交给 {@code of(byte[])}。 */
    private static Binary256 farlands$get(long[] raw, int offset) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 4; i++) {
            long word = raw[offset + i];
            for (int b = 0; b < 8; b++) {
                bytes[i * 8 + b] = (byte) (word >>> (56 - b * 8));
            }
        }
        return Binary256.of(bytes);
    }

    private static void writeTo(FriendlyByteBuf buffer, ExactPositionPacket pkt) {
        buffer.writeVarInt(pkt.entityId());
        for (long value : pkt.position()) {
            buffer.writeLong(value);
        }
        for (long value : pkt.previous()) {
            buffer.writeLong(value);
        }
    }

    private static ExactPositionPacket readFrom(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        long[] position = new long[AXIS_LONGS];
        for (int i = 0; i < AXIS_LONGS; i++) {
            position[i] = buffer.readLong();
        }
        long[] previous = new long[AXIS_LONGS];
        for (int i = 0; i < AXIS_LONGS; i++) {
            previous[i] = buffer.readLong();
        }
        return new ExactPositionPacket(entityId, position, previous);
    }

    @Override
    public Type<ExactPositionPacket> type() {
        return TYPE;
    }
}
