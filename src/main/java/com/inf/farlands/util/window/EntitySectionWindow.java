package com.inf.farlands.util.window;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * 实体 section 窗口并集，服务端主线程每 tick 更新，按维度分表。
 *
 * <p>键是维度。跨维度共用一张表会让别的维度玩家的 Y 带抬脚本维度的窗口：带内误放行，带外漏放行。
 * 没有玩家的维度窗口为空，该维度整体冻结，这是本 port 的既定语义。
 *
 * <p>热路径：{@link #inAnyWindow} 在每方块 tick 与每实体 tick 上被调用，所以缓存「上次维度加对应
 * 区间」一对字段，连续同维度的调用退化成一次 volatile 读。
 */
public final class EntitySectionWindow {

    private static final int[] EMPTY = new int[0];

    /** 维度到该维度的有序不重叠区间 [min0, max0, min1, max1, ...]。 */
    private static final Map<ResourceKey<Level>, int[]> WINDOWS = new ConcurrentHashMap<>();

    /** 上次查过的维度与它的区间，供热路径免掉一次 map 查表。 */
    private static volatile ResourceKey<Level> lastDimension;

    private static volatile int[] lastRanges = EMPTY;

    private EntitySectionWindow() {
    }

    /**
     * 按维度重建窗口并集，主线程每 tick 一次。
     *
     * <p>本 tick 没有玩家的维度要把窗口清掉，否则玩家离开后那条带还留着，该维度会照旧误放行。
     */
    public static void update(List<ServerPlayer> players) {
        Map<ResourceKey<Level>, List<int[]>> byDimension = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            int c = Mth.floorDiv(p.getBlockY(), 16);
            byDimension.computeIfAbsent(p.level().dimension(), k -> new ArrayList<>())
                    .add(new int[] { c - FarlandsConfig.verticalSimulationDistance,
                            c + FarlandsConfig.verticalSimulationDistance });
        }
        for (Map.Entry<ResourceKey<Level>, List<int[]>> e : byDimension.entrySet()) {
            WINDOWS.put(e.getKey(), merge(e.getValue()));
        }
        WINDOWS.keySet().retainAll(byDimension.keySet());
        lastDimension = null;
    }

    /** 停服时清空窗口带。update 在玩家列表为空时本就会自清，这里只是把新世界启动到首个 tick 之间也覆盖掉。 */
    public static void clear() {
        WINDOWS.clear();
        lastDimension = null;
        lastRanges = EMPTY;
    }

    /** 该维度的窗口并集区间；该维度没有玩家时为空数组。 */
    public static int[] ranges(ResourceKey<Level> dimension) {
        ResourceKey<Level> last = lastDimension;
        if (last != null && last.equals(dimension)) {
            return lastRanges;
        }
        int[] ranges = WINDOWS.get(dimension);
        if (ranges == null) {
            ranges = EMPTY;
        }
        lastDimension = dimension;
        lastRanges = ranges;
        return ranges;
    }

    /** 是否还有任何维度存在窗口。给「无窗口即早退」那类调用点用。 */
    public static boolean hasAnyWindow() {
        return !WINDOWS.isEmpty();
    }

    /** 该维度的该段是否落在窗口内。 */
    public static boolean inAnyWindow(ResourceKey<Level> dimension, int sectionY) {
        int[] r = ranges(dimension);
        for (int i = 0; i < r.length; i += 2) {
            if (sectionY >= r[i] && sectionY <= r[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** 该维度的该段是否在该维度全部窗口区间及余量 margin 之外。 */
    public static boolean isOutsideAllWindows(ResourceKey<Level> dimension, int sectionY, int margin) {
        int[] r = ranges(dimension);
        for (int i = 0; i < r.length; i += 2) {
            if (sectionY >= r[i] - margin && sectionY <= r[i + 1] + margin) {
                return false;
            }
        }
        return true;
    }

    /** 遍历该维度并集区间内全部 sectionY，供地形管线触发收集用。区间合并后数量有限。 */
    public static void forEachSectionInAnyWindow(ResourceKey<Level> dimension, IntConsumer consumer) {
        int[] r = ranges(dimension);
        for (int i = 0; i < r.length; i += 2) {
            for (int sy = r[i]; sy <= r[i + 1]; sy++) {
                consumer.accept(sy);
            }
        }
    }

    /**
     * 全部维度的并集，只给拿不到维度的调用点兜底。
     *
     * <p>每次调用都现合并，属低频路径。真实路径上不应出现，出现了要能被看到，所以调用点要限流打一条
     * 日志。
     */
    public static int[] unionAllDimensions() {
        List<int[]> all = new ArrayList<>();
        for (int[] r : WINDOWS.values()) {
            for (int i = 0; i < r.length; i += 2) {
                all.add(new int[] { r[i], r[i + 1] });
            }
        }
        return merge(all);
    }

    /** 区间按起点排序后合并，相邻或重叠即并成一段。元素是 {min, max}。 */
    private static int[] merge(List<int[]> list) {
        if (list.isEmpty()) {
            return EMPTY;
        }
        list.sort(Comparator.comparingInt(a -> a[0]));
        int[] merged = new int[list.size() * 2];
        int m = 0;
        for (int[] iv : list) {
            if (m == 0) {
                merged[m++] = iv[0];
                merged[m++] = iv[1];
            } else if (iv[0] <= merged[m - 1] + 1) {
                merged[m - 1] = Math.max(merged[m - 1], iv[1]);
            } else {
                merged[m++] = iv[0];
                merged[m++] = iv[1];
            }
        }
        return Arrays.copyOf(merged, m);
    }
}
