package com.inf.farlands.network.position;

import com.inf.farlands.InfsFarlands;

import com.inf.long2.binaries.binary1280.Binary1280;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 一个实体的精确位置：当前三轴与旧位置三轴，各按 1280 位原样带上。
 *
 * <p>
 * 原版的位置包装的是 {@code PositionMoveRotation}，位置是 {@code Vec3}，三个 double，而且那是 record 分量，
 * 无法扩展。所以精确值走这条并行 payload：vanilla 的包先把位置、包围盒、缓存与旧位置都落好，本包后到，只覆写
 * 槽位。
 *
 * <p>
 * 每轴二十个 long，是 {@code Binary1280.toBytes} 的 160 字节按 8 字节大端依次切出的二十段，反向用
 * {@code of(byte[])}。这两个入口逐位互逆，所以线上就是「原样搬 160 字节」，不经过任何舍入。
 *
 * <p>
 * 旧位置也带，是因为客户端根本不从服务端收旧位置：它用自己上一帧的旧位置与包里的 change 现算，那个算式在
 * double 上做，插值起点因此同样会偏。
 */
public record ExactPositionPacket(int entityId, long[] position, long[] previous) implements CustomPacketPayload {

    /** 一条坐标的组数与字节数：二十个 long 即 160 字节。 */
    private static final int LONG_WORDS = 20;

    /** 三轴各二十个 long。 */
    public static final int AXIS_LONGS = LONG_WORDS * 3;

    public static final Type<ExactPositionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "exact_position"));

    public static final StreamCodec<FriendlyByteBuf, ExactPositionPacket> STREAM_CODEC = StreamCodec.of(
            ExactPositionPacket::writeTo,
            ExactPositionPacket::readFrom);

    /** 三轴打包成六十个 long。 */
    public static long[] pack(Binary1280 x, Binary1280 y, Binary1280 z) {
        long[] raw = new long[AXIS_LONGS];
        farlands$put(raw, 0, x);
        farlands$put(raw, LONG_WORDS, y);
        farlands$put(raw, LONG_WORDS * 2, z);
        return raw;
    }

    /** 六十个 long 还原成三轴。 */
    public static Binary1280[] unpack(long[] raw) {
        return new Binary1280[] {
                farlands$get(raw, 0),
                farlands$get(raw, LONG_WORDS),
                farlands$get(raw, LONG_WORDS * 2)
        };
    }

    /** 一个 1280 位值切成二十个 long：先取 160 字节大端编码，再按 8 字节一组拼回。 */
    private static void farlands$put(long[] raw, int offset, Binary1280 value) {
        byte[] bytes = value.toBytes();
        for (int i = 0; i < LONG_WORDS; i++) {
            long word = 0L;
            for (int b = 0; b < 8; b++) {
                word = (word << 8) | (bytes[i * 8 + b] & 0xFFL);
            }
            raw[offset + i] = word;
        }
    }

    /** 二十个 long 拼回一个 1280 位值：摊成 160 字节大端，再交给 {@code of(byte[])}。 */
    private static Binary1280 farlands$get(long[] raw, int offset) {
        byte[] bytes = new byte[LONG_WORDS * Long.BYTES];
        for (int i = 0; i < LONG_WORDS; i++) {
            long word = raw[offset + i];
            for (int b = 0; b < 8; b++) {
                bytes[i * 8 + b] = (byte) (word >>> (56 - b * 8));
            }
        }
        return Binary1280.of(bytes);
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
