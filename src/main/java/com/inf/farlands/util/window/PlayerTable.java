package com.inf.farlands.util.window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.inf.farlands.util.pos.ExactPosition;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.Level;

/**
 * 每 tick 一份的驱动玩家表：谁是这个世界此刻的驱动者，以及他们在不在可玩界内。
 *
 * <p>
 * 存在的理由是坐标的两种表示。位置真值在 256 位槽位里，可以超出 int；而窗口、票、发包、读回这些下游只认
 * {@code getBlockY()} 与 {@code chunkPosition()} 这两份 int 缓存，{@code |v| ≥ 2^31} 时它们饱和到 int 两端，
 * 于是窗口中心与票圈钉在饱和处不动，真值附近永远没有区块，饱和处却一直被生成。
 *
 * <p>
 * 本类把判定收成一处：三轴取 {@code farlands$cellCoord} 的精确格号，过 {@code WorldBounds.inBlock}。界外的玩家
 * 仍然在表里，只是 {@code inBounds} 为假，并且不带区块坐标与段中心。下游读表时据此跳过，不再各自从 int 缓存现算。
 *
 * <p>
 * 三种状态必须分开：「界内」「界外」「本 tick 没刷过」。前两者是条目上的字段，第三者由 {@link #refreshed}
 * 表达，判据是「该维度本 tick 有过玩家」。{@code Scheduler} 那条持久重投原先用「有玩家而窗口为空」代替
 * 第三种状态，界外玩家被跳过后两者会混同，所以标记单独给。
 *
 * <p>
 * 发布方式与它替换掉的快照一致：整体替换一个不可变 List，字段是 volatile。生成池线程会读它，所以不能逐条改。
 * 表在 {@code driveWindowAndTerrain} 的头部重建，同一次推进里 {@code refreshTickState} 还会再刷一次，玩家数
 * 个位数，代价可忽略；不做按 tick 幂等，因为 {@code Scheduler} 在换维度时要靠这一次中途刷新拿到新维度的条目。
 */
public final class PlayerTable {

    /**
     * 一名玩家在本 tick 的位置。
     *
     * <p>
     * {@code cellX/Y/Z} 是精确格号，long。{@code chunkX/chunkZ} 与 {@code sectionCenterY} 只在 {@code inBounds}
     * 为真时有意义，界内意味着三轴都落在 int 内，位移四位不会越界。
     */
    public record Entry(ServerPlayer player, ResourceKey<Level> dimension,
            long cellX, long cellY, long cellZ, boolean inBounds,
            int chunkX, int chunkZ, int sectionCenterY, int viewDistance) {
    }

    private static volatile List<Entry> entries = List.of();

    private static volatile Set<ResourceKey<Level>> refreshed = Set.of();

    private PlayerTable() {
    }

    /** 重建整张表。主线程调，一次推进的头部。 */
    public static void refresh(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            entries = List.of();
            refreshed = Set.of();
            return;
        }
        List<Entry> built = new ArrayList<>(players.size());
        Set<ResourceKey<Level>> dimensions = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            built.add(farlands$entry(players.get(i), dimensions));
        }
        entries = List.copyOf(built);
        refreshed = Set.copyOf(dimensions);
    }

    private static Entry farlands$entry(ServerPlayer player, Set<ResourceKey<Level>> dimensions) {
        ResourceKey<Level> dimension = player.level().dimension();
        dimensions.add(dimension);
        ExactPosition exact = (ExactPosition) player;
        long cellX = exact.farlands$cellCoord(ExactPosition.POS_X);
        long cellY = exact.farlands$cellCoord(ExactPosition.POS_Y);
        long cellZ = exact.farlands$cellCoord(ExactPosition.POS_Z);
        boolean inBounds = WorldBounds.inBlock(cellX, cellY, cellZ);
        int view = player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned positioned
                ? positioned.viewDistance()
                : 8;
        if (!inBounds) {
            return new Entry(player, dimension, cellX, cellY, cellZ, false, 0, 0, 0, view);
        }
        return new Entry(player, dimension, cellX, cellY, cellZ, true,
                (int) (cellX >> 4), (int) (cellZ >> 4), (int) (cellY >> 4), view);
    }

    /** 本 tick 的全部条目，含界外。不可变。 */
    public static List<Entry> entries() {
        return entries;
    }

    /** 该玩家的条目；不在表里返回 null，调用方要能处理这一支。 */
    public static Entry entryOf(ServerPlayer player) {
        List<Entry> current = entries;
        for (int i = 0; i < current.size(); i++) {
            Entry entry = current.get(i);
            if (entry.player() == player) {
                return entry;
            }
        }
        return null;
    }

    /** 该维度本 tick 是否有过玩家。代替「窗口为空即还没刷」那条判据。 */
    public static boolean refreshed(ResourceKey<Level> dimension) {
        return refreshed.contains(dimension);
    }

    /** 该维度界内条目里离该 chunk 最近的切比雪夫距离；无条目时为 0，与旧快照同值。 */
    public static int nearestDistance(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        int best = Integer.MAX_VALUE;
        List<Entry> current = entries;
        for (int i = 0; i < current.size(); i++) {
            Entry e = current.get(i);
            if (!e.inBounds() || !e.dimension().equals(dimension)) {
                continue;
            }
            int d = Math.max(Math.abs(chunkX - e.chunkX()), Math.abs(chunkZ - e.chunkZ()));
            if (d < best) {
                best = d;
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /**
     * 该维度界内条目里是否有玩家在这个 chunk 的视距加一格之内。
     *
     * <p>
     * 与 {@link #nearestDistance} 分开：后者无条目时给 0 是为排序服务的旧口径，拿它当准入门会把「没有界内玩家」
     * 判成「就在身边」，于是整片都放行。
     */
    public static boolean isHorizontallyNear(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        List<Entry> current = entries;
        for (int i = 0; i < current.size(); i++) {
            Entry e = current.get(i);
            if (!e.inBounds() || !e.dimension().equals(dimension)) {
                continue;
            }
            int d = Math.max(Math.abs(chunkX - e.chunkX()), Math.abs(chunkZ - e.chunkZ()));
            if (d <= e.viewDistance() + 1) {
                return true;
            }
        }
        return false;
    }

    /** 该维度界内条目的最大视距；无条目时 8，与旧快照同值。 */
    public static int viewDistance(ResourceKey<Level> dimension) {
        int view = 0;
        List<Entry> current = entries;
        for (int i = 0; i < current.size(); i++) {
            Entry e = current.get(i);
            if (e.inBounds() && e.dimension().equals(dimension) && e.viewDistance() > view) {
                view = e.viewDistance();
            }
        }
        return view == 0 ? 8 : view;
    }

    /** 卸关卡或停服时清空，避免表里留下旧世界的玩家。 */
    public static void clear() {
        entries = List.of();
        refreshed = Set.of();
    }

    /** 供 roster 比对用：本 tick 在界内的玩家 id。 */
    public static Set<UUID> inBoundsIds() {
        Set<UUID> ids = new HashSet<>();
        List<Entry> current = entries;
        for (int i = 0; i < current.size(); i++) {
            Entry e = current.get(i);
            if (e.inBounds()) {
                ids.add(e.player().getUUID());
            }
        }
        return ids;
    }
}
