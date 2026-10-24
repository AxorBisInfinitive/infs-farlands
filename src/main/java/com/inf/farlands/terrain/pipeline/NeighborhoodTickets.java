package com.inf.farlands.terrain.pipeline;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.terrain.structure.StructureDriver;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 驱动面 ±8 邻域的保加载票。
 *
 * <p>门要求中心的 ±{@link StructureDriver#STRUCTURE_READ_RADIUS} 那 289 格都在
 * {@code visibleChunkMap} 里，而玩家自己的票圈只有视距那么大：{@code GenQueue.isNearPlayer} 现在按
 * 切比雪夫判「距最近玩家 ≤ 视距加一」，驱动面是那张方盘。于是驱动面边缘那几圈的 ±8 落在票圈之外，
 * 门恒不过；这一圈票就是让门能过的前提。
 *
 * <p>本类给每个玩家在每个维度持一颗 {@code radius = 视距 + STRUCTURE_READ_RADIUS + 2} 的票，覆盖整个
 * 驱动面的 ±8。半径里的 2 就是上面那个 includeNeighbors 缓冲。
 *
 * <p>票型复用 {@link GenQueue#STRUCTURE_PIN_TICKET}：它是 {@code NO_TIMEOUT + FLAG_LOADING}，只加载不
 * simulate，所以多出来的圈不进 tick 与刷怪面。票位等级由半径决定，与作业期由所有权表加的
 * {@code GEN_WORK_TICKET} 是各自独立的项，互不干扰。
 *
 * <p>只加载不生成：多出来的圈里没有段被物化，因为视距外不铺群系，而 {@code Scheduler} 的准入、
 * 扫描与 farlandsStructureReady 的回投三条生成入口都够不着它们，所以它们是纯壳。
 *
 * <p>撤票必须传同一个半径：{@code TicketStorage} 按「类型加票位等级」匹配删除，半径不符就删不掉，票会
 * 沿飞行路径累积。玩家退出与换维度都在本类里按上次记下的位置撤，不依赖外部登出钩子。
 */
public final class NeighborhoodTickets {

    /** 上次给该玩家铺票的位置、维度与所用半径。半径也记，因为它随视距配置变。 */
    private record State(ResourceKey<Level> dimension, long chunkKey, int radius) {
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /**
     * 任务期持有：维度到 chunk 到票半径。与玩家那条路径各用一张表，互不干扰。
     *
     * <p>用途是「不以玩家为中心」的驱动面：预生成由 Chunky 之类的任务指定区块，那些区块离玩家很远，
     * 玩家环票够不着，所以它们的 ±8 由本类的 hold 保着。持有方在驱动一块之前先 {@link #hold}，
     * 那块亮完或超时后 {@link #release}。
     */
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> HOLDS = new ConcurrentHashMap<>();

    private NeighborhoodTickets() {
    }

    /** 每 tick 由主线程调用。票操作非线程安全，只能在主线程做。 */
    public static void tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<UUID> roster = new HashSet<>();
        for (ServerPlayer player : players) {
            roster.add(player.getUUID());
            reconcile(server, player);
        }
        Iterator<Map.Entry<UUID, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> entry = it.next();
            if (roster.contains(entry.getKey())) {
                continue;
            }
            release(server, entry.getValue());
            it.remove();
        }
    }

    /** 位置、维度或半径任一变化就换票：先加新的再撤旧的，重复加同型同级只重置计时。 */
    private static void reconcile(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        ChunkPos pos = player.chunkPosition();
        int radius = radiusFor(player);
        State state = STATES.get(player.getUUID());
        if (state != null && state.dimension().equals(level.dimension())
                && state.chunkKey() == pos.pack() && state.radius() == radius) {
            return;
        }
        level.getChunkSource().addTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, pos, radius);
        if (state != null) {
            release(server, state);
        }
        STATES.put(player.getUUID(), new State(level.dimension(), pos.pack(), radius));
    }

    /** 撤一张票。维度按状态里记的取，换维度时它已经不是玩家的当前维度。 */
    private static void release(MinecraftServer server, State state) {
        ServerLevel level = server.getLevel(state.dimension());
        if (level != null) {
            level.getChunkSource().removeTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET,
                    ChunkPos.unpack(state.chunkKey()), state.radius());
        }
    }

    /** 该玩家驱动面的半径加 ±8 与 includeNeighbors 缓冲。 */
    private static int radiusFor(ServerPlayer player) {
        ChunkTrackingView view = player.getChunkTrackingView();
        int viewDistance = view instanceof ChunkTrackingView.Positioned positioned ? positioned.viewDistance() : 8;
        return viewDistance + StructureDriver.STRUCTURE_READ_RADIUS + 2;
    }

    /**
     * 给某个中心铺一颗 {@code radius = STRUCTURE_READ_RADIUS} 的票，覆盖它的整个 ±8。幂等。
     *
     * <p>半径 8 让中心拿到 {@code FULL - 8} 的票位等级、±8 那一圈拿到不超过 {@code FULL} 的等级，
     * 于是那一圈都进「已加载」。中心本身通常已由持有方自己的票保着，这里只补那一圈。
     *
     * <p>返回本次是否真的铺下：持有方据此记住「这颗是自己加的」，撤票时只撤自己加过的那些。同一个中心被
     * 多个持有方需要时，只有第一个会拿到真，撤票因此不会把别人的票撤掉。
     */
    public static boolean hold(ServerLevel level, ChunkPos center) {
        int radius = StructureDriver.STRUCTURE_READ_RADIUS;
        Map<Long, Integer> holds = HOLDS.computeIfAbsent(level.dimension(),
                k -> new ConcurrentHashMap<>());
        if (holds.putIfAbsent(center.pack(), radius) == null) {
            level.getChunkSource().addTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, center, radius);
            return true;
        }
        return false;
    }

    /** 撤回 {@link #hold} 铺的那颗票。半径从持有表里取，保证与铺下去的那次逐字相同。 */
    public static void release(ServerLevel level, ChunkPos center) {
        Map<Long, Integer> holds = HOLDS.get(level.dimension());
        if (holds == null) {
            return;
        }
        Integer radius = holds.remove(center.pack());
        if (radius != null) {
            level.getChunkSource().removeTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, center, radius);
        }
    }

    /** 停服时清状态。票随 level 消亡，不逐个撤。 */
    public static void clearWorldState() {
        STATES.clear();
        HOLDS.clear();
    }
}
