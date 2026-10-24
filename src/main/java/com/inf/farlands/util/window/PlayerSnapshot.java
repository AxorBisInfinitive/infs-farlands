package com.inf.farlands.util.window;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家位置的每 tick 快照，供排序与准入读。
 *
 * <p>不读快照的写法是每次调用都遍历玩家列表，复杂度是「每个查询点乘玩家数」。排序与准入都是逐
 * chunk 的查询点，所以两者都读快照，快照每 tick 取一次。
 *
 * <p>维度之外还带视距：视距按玩家取最大值，而它在 {@code Positioned} 上，读一次比每次遍历玩家便宜。
 */
public final class PlayerSnapshot {

    /**
     * 一个玩家在这一 tick 的位置与视距。
     *
     * @param dimension   所在维度
     * @param chunkX      所在 chunk 的 x
     * @param chunkZ      所在 chunk 的 z
     * @param blockY      方块 Y，窗口与准入的竖直判据用
     * @param viewDistance 该玩家的视距，取不到时 8
     */
    public record Entry(ResourceKey<Level> dimension, int chunkX, int chunkZ, int blockY, int viewDistance) {
    }

    private static volatile List<Entry> entries = List.of();

    private PlayerSnapshot() {
    }

    /** 主线程每 tick 抓一次。必须在任何读快照的判断之前。 */
    public static void capture(List<ServerPlayer> players) {
        if (players.isEmpty()) {
            entries = List.of();
            return;
        }
        List<Entry> list = new ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            ChunkPos cp = p.chunkPosition();
            int view = p.getChunkTrackingView() instanceof ChunkTrackingView.Positioned positioned
                    ? positioned.viewDistance()
                    : 8;
            list.add(new Entry(p.level().dimension(), cp.x(), cp.z(), p.getBlockY(), view));
        }
        entries = List.copyOf(list);
    }

    /** 本 tick 的快照，只读。 */
    public static List<Entry> entries() {
        return entries;
    }

    /**
     * 快照里出现的维度，按出现顺序去重，元素是维度 id 的字符串。
     *
     * <p>只给冻结期的读数用。准入读的是 {@link #entries()} 的逐条内容，这一格回答「这份快照里有没有
     * 那个维度」；与窗口表那一侧的回答并排看，就能分开「泵没跑」与「泵跑了但没写进这个维度」。
     */
    public static List<String> dimensions() {
        List<String> dims = new ArrayList<>();
        for (Entry e : entries) {
            String id = String.valueOf(e.dimension().identifier());
            if (!dims.contains(id)) {
                dims.add(id);
            }
        }
        return dims;
    }

    /** 该 chunk 到该维度最近玩家的 Chebyshev 距离；该维度无玩家时返回 0。 */
    public static int nearestDistance(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        int best = Integer.MAX_VALUE;
        for (Entry e : entries) {
            if (!e.dimension().equals(dimension)) {
                continue;
            }
            int d = Math.max(Math.abs(chunkX - e.chunkX()), Math.abs(chunkZ - e.chunkZ()));
            if (d < best) {
                best = d;
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /** 该维度的有效视距，按该维度玩家取最大值；无玩家时回退 8。 */
    public static int viewDistance(ResourceKey<Level> dimension) {
        int view = 0;
        for (Entry e : entries) {
            if (e.dimension().equals(dimension) && e.viewDistance() > view) {
                view = e.viewDistance();
            }
        }
        return view == 0 ? 8 : view;
    }
}
