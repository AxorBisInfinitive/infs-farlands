package com.inf.farlands.util.network;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.network.simulation.SimulationSystemsPacket;
import com.inf.farlands.simulation.model.SimulationData;
import com.inf.farlands.simulation.serialize.SimulationIO;
import com.inf.farlands.terrain.registry.SystemsData;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 每个玩家一条的模拟层维度选择下发。
 *
 * <p>
 * 与 farlands 五族那条同形，也只有两种发送时机：进服与换维度。选择在 level 建好之后不再变，所以记下每人
 * 上次发出的维度，与当前维度不同才发；进服那次天然不同，因此不需要玩家加入的事件钩子。玩家退出靠每 tick
 * 的名单比对清理。
 *
 * <p>
 * 取数走 {@link SimulationIO#systemsFor(MinecraftServer)}，与 level 构造时取的是同一份按 server 缓存的实例，
 * 两处因此不会分叉。维度缺条目补 VOID，不发 null。有玩家时才解析这份档案：没有在线的玩家就没有收件人，
 * 不必为一次下发去碰档案层。
 */
public final class SimulationSystemsSender {

    private SimulationSystemsSender() {
    }

    private static final Map<UUID, ResourceKey<Level>> SENT_DIMENSIONS = new ConcurrentHashMap<>();

    public static void tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            SimulationData data = SimulationIO.systemsFor(server);
            for (ServerPlayer player : players) {
                ResourceKey<Level> dimension = player.level().dimension();
                if (dimension.equals(SENT_DIMENSIONS.get(player.getUUID()))) {
                    continue;
                }
                SystemsData.SystemSelection selection = SimulationIO.selection(data, dimension.identifier());
                player.connection.send(new ClientboundCustomPayloadPacket(
                        new SimulationSystemsPacket(dimension, selection)));
                SENT_DIMENSIONS.put(player.getUUID(), dimension);
            }
        }
        Set<UUID> roster = new HashSet<>();
        for (ServerPlayer player : players) {
            roster.add(player.getUUID());
        }
        SENT_DIMENSIONS.keySet().retainAll(roster);
    }

    /** 停服清空已发维度记录，否则新世界第一 tick 会把每人上次的维度当成没变而漏发。 */
    public static void clearWorldState() {
        SENT_DIMENSIONS.clear();
    }
}
