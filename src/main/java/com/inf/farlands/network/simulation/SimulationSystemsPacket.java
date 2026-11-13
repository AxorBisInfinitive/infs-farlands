package com.inf.farlands.network.simulation;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.registry.SystemsData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 某维度模拟层系统选择的下发包。
 *
 * <p>
 * 只带一族：模拟层按维度只有一个选择。合进 farlands 五族那个包有两条路都不成立，改
 * {@link SystemsData.LevelSelection} 会把两层的磁盘格式绑死，在外层再包一个 record 则每次都要背五个族字段。
 * 两层的族数将来不同，共用一个包会让字段长期错位。
 *
 * <p>
 * 选择本身用 {@link SystemsData.SystemSelection} 的 codec 编解码，与 {@code simulation.dat} 同一份定义：
 * 盘上怎么写、线上就怎么走，将来加参数类型只改一处。
 *
 * <p>
 * 带维度字段，接收端按维度入表，跨维度在途的旧包因此顶不掉当前维度的显示。
 */
public record SimulationSystemsPacket(ResourceKey<Level> dimension, SystemsData.SystemSelection selection)
        implements CustomPacketPayload {

    public static final Type<SimulationSystemsPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "simulation_systems"));

    public static final StreamCodec<FriendlyByteBuf, SimulationSystemsPacket> STREAM_CODEC = StreamCodec.of(
            SimulationSystemsPacket::writeTo,
            SimulationSystemsPacket::readFrom);

    /** 与档案同一份 codec，经 NbtOps 走线；读侧用默认配额。 */
    private static final StreamCodec<ByteBuf, SystemsData.SystemSelection> SELECTION_CODEC = ByteBufCodecs
            .fromCodec(SystemsData.SystemSelection.CODEC);

    public static void writeTo(FriendlyByteBuf buffer, SimulationSystemsPacket pkt) {
        buffer.writeResourceKey(pkt.dimension());
        SELECTION_CODEC.encode(buffer, pkt.selection());
    }

    public static SimulationSystemsPacket readFrom(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
        return new SimulationSystemsPacket(dimension, SELECTION_CODEC.decode(buffer));
    }

    @Override
    public Type<SimulationSystemsPacket> type() {
        return TYPE;
    }
}
