package com.inf.farlands.network.position;

import com.inf.farlands.InfsFarlands;

import com.inf.long2.binaries.Binary256;

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
 * 每轴四个 long，取自 {@code Binary256.getRawLong}，反向用 {@code fromRawBits}。不走 {@code toBytes} 是为了
 * 免去字节序这一层约定。
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
                Binary256.fromRawBits(raw[0], raw[1], raw[2], raw[3]),
                Binary256.fromRawBits(raw[4], raw[5], raw[6], raw[7]),
                Binary256.fromRawBits(raw[8], raw[9], raw[10], raw[11])
        };
    }

    private static void farlands$put(long[] raw, int offset, Binary256 value) {
        for (int i = 0; i < 4; i++) {
            raw[offset + i] = value.getRawLong(i);
        }
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
