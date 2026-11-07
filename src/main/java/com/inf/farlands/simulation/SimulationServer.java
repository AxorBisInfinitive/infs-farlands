package com.inf.farlands.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.network.simulation.SimulationSectionPacket;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import com.inf.farlands.simulation.model.SimulationSectionStage;
import com.inf.farlands.simulation.model.SimulationStore;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 服务端的模拟层驱动：每 tick 维护段的存活集合，并按玩家视距下发。
 *
 * <p>
 * 生命周期照原版的 chunk：视距并集里没有的段释放，新进的段创建并跑一次生产者，首次发送整段，之后只发脏段。
 * 与 chunk 的差别只有一条：这里不持久化，段释放即丢。
 *
 * <p>
 * 玩家轮换用 {@code retainAll} 自清，与 {@code ChunkDataSender} 同形，不额外挂玩家退出钩子。换维度时该维度的
 * 已发集合整份作废，否则旧维度的段键会被当成新维度已发过的。
 *
 * <p>
 * 全部在服务端主线程调用。{@link SimulationStore} 的内层表是普通 map，正是靠这一条成立。
 */
public final class SimulationServer {

    /** 每玩家每维度已经发过哪些段。 */
    private static final Map<UUID, Map<ResourceKey<Level>, Set<SimulationSectionKey>>> SENT = new HashMap<>();

    /** 临时读数，用完删除：上一 tick 的并集大小与存储大小。 */
    private static final Map<ResourceKey<Level>, int[]> LAST_TICK = new HashMap<>();

    private SimulationServer() {
    }

    /** 一个玩家本 tick 的可见段集合，带中心坐标与视距供排序与读数。 */
    private record View(long centerX, long centerZ, int radius, boolean positioned,
            Set<SimulationSectionKey> keys) {
    }

    /** 每 tick 入口，由 {@code FarlandsTick.atEnd} 在窗口驱动之后调用。 */
    public static void tick(MinecraftServer server) {
        Set<UUID> roster = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level, roster);
        }
        SENT.keySet().retainAll(roster);
    }

    private static void tickLevel(ServerLevel level, Set<UUID> roster) {
        ResourceKey<Level> dimension = level.dimension();
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
                SimulationLifecycle.release(level, key);
            }
            return;
        }

        Map<UUID, View> views = new HashMap<>(players.size());
        Map<SimulationSectionKey, Integer> wantCount = new HashMap<>();
        Set<SimulationSectionKey> union = new HashSet<>();
        for (ServerPlayer player : players) {
            View view = view(player);
            views.put(player.getUUID(), view);
            roster.add(player.getUUID());
            union.addAll(view.keys());
            for (SimulationSectionKey key : view.keys()) {
                wantCount.merge(key, 1, Integer::sum);
            }
        }

        for (SimulationSectionKey key : union) {
            SimulationLifecycle.ensureSection(level, key);
        }
        for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
            if (!union.contains(key)) {
                SimulationLifecycle.release(level, key);
            }
        }

        int[] totals = new int[2];
        Map<SimulationSectionKey, Integer> queued = new HashMap<>();
        for (ServerPlayer player : players) {
            send(level, player, views.get(player.getUUID()), queued, totals);
        }
        // 脏标记只在「所有想要它的玩家这一 tick 都收到了」时才清，否则留在下一 tick 补发。
        for (Map.Entry<SimulationSectionKey, Integer> entry : queued.entrySet()) {
            SimulationSectionData data = SimulationStore.get(dimension, entry.getKey());
            if (data != null && entry.getValue() >= wantCount.getOrDefault(entry.getKey(), 0)) {
                data.clearDirty();
            }
        }
        // 临时读数，用完删除。
        farlands$tickReadout(dimension, players.size(), union.size(), totals);
    }

    /** 临时读数，用完删除：总量相对上一 tick 掉三成以上就打一行。 */
    private static void farlands$tickReadout(ResourceKey<Level> dimension, int players, int union, int[] totals) {
        int store = SimulationStore.size(dimension);
        int[] last = LAST_TICK.get(dimension);
        LAST_TICK.put(dimension, new int[] { union, store });
        if (last == null || last[0] == 0) {
            return;
        }
        boolean dropped = store * 10 < last[1] * 7 || union * 10 < last[0] * 7;
        if (dropped) {
            InfsFarlands.LOGGER.info("FLSIM tick players={} union={} store={} load={} rm={}", players, union, store,
                    totals[0], totals[1]);
        }
    }

    /**
     * 该玩家视距内的段：横向按圆盘，纵向取整窗，每段一个键。
     *
     * <p>
     * 竖直方向不筛。视距内每一段都必须存在，空腔才有对象承载光：曾经按每 6 段一层枚举，段与段之间的空腔
     * 没有对象，任何跨出段边界的取光都落到「无段」上得到 0，表现为段底发黑。层距如今不再是存在性口径，
     * 哪些段有方块完全由选中的系统决定。
     *
     * <p>
     * 窗口不外扩：边缘的段都是空的，没有面可黑；圆盘边缘朝外的面背向观察者。
     */
    private static View view(ServerPlayer player) {
        ExactPosition exact = (ExactPosition) player;
        long centerX = exact.farlands$cellCoord(ExactPosition.POS_X) >> 4;
        long centerY = exact.farlands$cellCoord(ExactPosition.POS_Y) >> 4;
        long centerZ = exact.farlands$cellCoord(ExactPosition.POS_Z) >> 4;

        ChunkTrackingView tracking = player.getChunkTrackingView();
        boolean positioned = tracking instanceof ChunkTrackingView.Positioned;
        int radius = positioned ? ((ChunkTrackingView.Positioned) tracking).viewDistance() : 8;
        long radiusSqr = (long) radius * radius;
        int half = FarlandsConfig.verticalSimulationDistance;
        Set<SimulationSectionKey> keys = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if ((long) dx * dx + (long) dz * dz > radiusSqr) {
                    continue;
                }
                for (long sy = centerY - half; sy <= centerY + half; sy++) {
                    keys.add(new SimulationSectionKey(centerX + dx, sy, centerZ + dz));
                }
            }
        }
        return new View(centerX, centerZ, radius, positioned, keys);
    }

    /** 对单个玩家做一次差分并限量发送。 */
    private static void send(ServerLevel level, ServerPlayer player, View view,
            Map<SimulationSectionKey, Integer> queued, int[] totals) {
        ResourceKey<Level> dimension = level.dimension();
        Map<ResourceKey<Level>, Set<SimulationSectionKey>> perDimension = SENT.computeIfAbsent(player.getUUID(),
                k -> new HashMap<>());
        perDimension.keySet().removeIf(key -> !key.equals(dimension));
        Set<SimulationSectionKey> sent = perDimension.computeIfAbsent(dimension, k -> new HashSet<>());

        List<SimulationSectionKey> removed = new ArrayList<>();
        sent.removeIf(key -> {
            if (view.keys().contains(key)) {
                return false;
            }
            removed.add(key);
            return true;
        });
        farlands$removalReadout(dimension, view, removed);

        List<SimulationSectionKey> candidates = new ArrayList<>();
        for (SimulationSectionKey key : view.keys()) {
            SimulationSectionData data = SimulationStore.get(dimension, key);
            // 未建或内容未定：此刻发出去只能是空壳，留到它升到 LIGHTED 之后的某一 tick。
            if (data == null || !SimulationSectionStage.isTerminal(data.stage())) {
                continue;
            }
            if (!sent.contains(key)) {
                candidates.add(key);
                continue;
            }
            if (data.isDirty()) {
                candidates.add(key);
            }
        }
        candidates.sort(Comparator.comparingLong(key -> distanceSqr(key, view)));

        int budget = FarlandsConfig.sectionSendBytesPerTick;
        int used = 0;
        List<SimulationSectionPacket.SectionEntry> loaded = new ArrayList<>();
        for (SimulationSectionKey key : candidates) {
            SimulationSectionData data = SimulationStore.get(dimension, key);
            if (data == null) {
                continue;
            }
            int size = SimulationSectionPacket.estimatedSize(data);
            if (used > 0 && used + size > budget) {
                break;
            }
            loaded.add(SimulationSectionPacket.encode(key, data));
            used += size;
            sent.add(key);
            queued.merge(key, 1, Integer::sum);
        }
        totals[0] += loaded.size();
        totals[1] += removed.size();
        if (loaded.isEmpty() && removed.isEmpty()) {
            return;
        }
        player.connection.send(new ClientboundCustomPayloadPacket(
                new SimulationSectionPacket(dimension, loaded, removed)));
    }

    /**
     * 临时读数，用完删除：卸载里出现离中心不超过 2 段的键就打一行。
     *
     * <p>
     * 正常走路时中心附近永远不离开视距，所以这条在正常游玩里零输出；一旦出现，就是圆盘整个失效。
     * 三个字段分开三种成因：{@code ys=1} 是段带位移，{@code viewPos=false} 是视距回退到 8，{@code store=0}
     * 是表被清空或走了玩家为空那一支。
     */
    private static void farlands$removalReadout(ResourceKey<Level> dimension, View view,
            List<SimulationSectionKey> removed) {
        boolean nearCenter = false;
        Set<Long> ys = new HashSet<>();
        int rmin = Integer.MAX_VALUE;
        int rmax = 0;
        for (SimulationSectionKey key : removed) {
            int distance = (int) Math.max(Math.abs(key.x() - view.centerX()),
                    Math.abs(key.z() - view.centerZ()));
            if (distance <= 2) {
                nearCenter = true;
            }
            ys.add(key.y());
            rmin = Math.min(rmin, distance);
            rmax = Math.max(rmax, distance);
        }
        if (!nearCenter) {
            return;
        }
        InfsFarlands.LOGGER.info("FLSIM rm n={} ys={} rmin={} rmax={} view={} viewPos={} store={}", removed.size(),
                ys.size(), rmin, rmax, view.radius(), view.positioned(), SimulationStore.size(dimension));
    }


    private static long distanceSqr(SimulationSectionKey key, View view) {
        long dx = key.x() - view.centerX();
        long dz = key.z() - view.centerZ();
        return dx * dx + dz * dz;
    }

    /** 停服清空已发记录与读数基线。 */
    public static void clearWorldState() {
        SENT.clear();
        LAST_TICK.clear();
    }
}
