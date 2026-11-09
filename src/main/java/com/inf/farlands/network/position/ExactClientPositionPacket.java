package com.inf.farlands.network.position;

import com.inf.farlands.InfsFarlands;

import com.inf.long2.binaries.Binary256;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端上报自己的精确位置：三轴各按 256 位原样带上。
 *
 * <p>
 * 存在的理由是精度：原版移动包里的位置是 {@code Vec3} 的三个 double，在 {@code 2^60} 处的步长是 256 格，服务端
 * 拿到的位置只能是 256 的整数倍。模拟层的段锚点按玩家 Y 取，那个量化会让层集整批跳变。
 *
 * <p>
 * 这条包只用于补精度：服务端的 double 位置照常由原版移动包写，本包只把真值写进实体的 256 位槽位，供
 * {@code cellCoord} 取用。它不参与服务端物理，也不改玩家位置。
 */
public record ExactClientPositionPacket(long[] position) implements CustomPacketPayload {

    public static final Type<ExactClientPositionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "exact_client_position"));

    public static final StreamCodec<FriendlyByteBuf, ExactClientPositionPacket> STREAM_CODEC = StreamCodec.of(
            ExactClientPositionPacket::writeTo,
            ExactClientPositionPacket::readFrom);

    /** 三轴打包成十二个 long，与实体精确位置包同一套编码。 */
    public static ExactClientPositionPacket of(Binary256 x, Binary256 y, Binary256 z) {
        return new ExactClientPositionPacket(ExactPositionPacket.pack(x, y, z));
    }

    /** 十二个 long 还原成三轴。 */
    public Binary256[] unpack() {
        return ExactPositionPacket.unpack(this.position);
    }

    private static void writeTo(FriendlyByteBuf buffer, ExactClientPositionPacket pkt) {
        for (long value : pkt.position()) {
            buffer.writeLong(value);
        }
    }

    private static ExactClientPositionPacket readFrom(FriendlyByteBuf buffer) {
        long[] position = new long[ExactPositionPacket.AXIS_LONGS];
        for (int i = 0; i < ExactPositionPacket.AXIS_LONGS; i++) {
            position[i] = buffer.readLong();
        }
        return new ExactClientPositionPacket(position);
    }

    @Override
    public Type<ExactClientPositionPacket> type() {
        return TYPE;
    }
}
