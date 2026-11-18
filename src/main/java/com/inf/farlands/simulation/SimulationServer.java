package com.inf.farlands.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.network.simulation.SimulationSectionPacket;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import com.inf.farlands.simulation.model.SimulationSectionStage;
import com.inf.farlands.simulation.model.SimulationStore;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.farlands.util.world.WorldBounds;
import love.crysh.long2.ints.Int1024;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 服务端的模拟层驱动：窗口变化时增删段，其余 tick 只派发脏段。
 *
 * <p>
 * 生命周期照原版的 chunk：视距与模拟区域的交里没有的段释放，新进的段创建并跑一次生产者，首次发送整段，之后
 * 只发脏段。与 chunk 的差别只有一条：这里不持久化，段释放即丢。
 *
 * <p>
 * 每 tick 的工作量只与本 tick 真正发出去的段数成正比，不与窗口大小成正比。窗口按玩家缓存，只有中心段、视距或
 * 维度变了才重建；并集是按维度增量维护的计数表，「谁想要某个键」与「并集」因此是同一份信息。
 *
 * <p>
 * 派发顺序由层桶给出：每个玩家窗口内、尚未发给该玩家的段，按水平切比雪夫距离分进 0 到 radius 共 radius 加一层
 * 桶，每 tick 从最内层往外取到字节预算满。不排序、不扫全窗。
 *
 * <p>
 * 已发集合、计数表与待分发集合都按维度分表：段键只有三轴段号，不含维度，跨维度同坐标会互相覆盖。
 *
 * <p>
 * 全部在服务端主线程调用。{@link SimulationStore} 的内层表是普通 map，正是靠这一条成立。
 */
public final class SimulationServer {

    /** 每玩家每维度已经发过哪些段。 */
    private static final Map<UUID, Map<ResourceKey<Level>, Set<SimulationSectionKey>>> SENT = new HashMap<>();

    /** 每玩家的窗口缓存。命中即本 tick 不重建键集，也不动计数表。 */
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    /**
     * 按维度的「这个键被几个玩家要」。
     *
     * <p>
     * 它同时就是并集：并集是计数大于 0 的键。每 tick 重建这张表的写法与窗口大小成正比，现在只在窗口变化时增减。
     */
    private static final Map<ResourceKey<Level>, Object2IntOpenHashMap<SimulationSectionKey>> WANT = new HashMap<>();

    /**
     * 按维度的「内容变更待分发」集合。
     *
     * <p>
     * 只有一个来源：生产收尾与读回完成时的登记，见 {@link #noteDirty}。每 tick 分发给窗口内各玩家的层桶，随即
     * 清空。窗口新进的段不进这张表，它们直接进桶；预算没发完的段也不进，它们留在桶里等下一 tick。
     */
    private static final Map<ResourceKey<Level>, Set<SimulationSectionKey>> DIRTY = new HashMap<>();

    /**
     * 下一个 tick 要做一次全表对账的维度。
     *
     * <p>
     * 增量路径只处理窗口变化的那批键，覆盖不到两类残留：上一个世界留下的表，以及落盘提交未完成、条目还留在表里的
     * 键。所以进程内首次、清世界状态、玩家集合变化、换维度时各置一次。按维度记而不记一个全局布尔：一个布尔会在
     * 第一个维度对账后就被清掉，其余维度永远等不到下一次触发。
     */
    private static final Set<ResourceKey<Level>> RESCAN = new HashSet<>();

    /** 是否要把当前每个维度都排进对账，下一次 {@link #tick} 消费。 */
    private static boolean rescanAll = true;

    /** 临时读数，用完删除：上一 tick 的并集大小与存储大小。 */
    private static final Map<ResourceKey<Level>, int[]> LAST_TICK = new HashMap<>();

    private SimulationServer() {
    }

    /**
     * 一个玩家的窗口：中心三轴、视距、它的键集，以及按层分的待发桶。
     *
     * <p>
     * {@code buckets} 是可变集合，{@link #send} 每 tick 从里面摘走已发的段。窗口变化时整份重建而不是原地挪动：
     * 层号相对玩家中心，中心一动，所有键的层号都可能变。竖直移动只改段带不改层号，地面上每次跨 chunk 才改。
     */
    private record View(ResourceKey<Level> dimension, Int1024 centerX, Int1024 centerY, Int1024 centerZ, int radius,
            boolean positioned, Set<SimulationSectionKey> keys, List<Set<SimulationSectionKey>> buckets) {

        /** 缓存是否仍然对应当前这一刻的窗口。 */
        boolean sameWindow(ResourceKey<Level> dimension, Int1024 centerX, Int1024 centerY, Int1024 centerZ, int radius,
                boolean positioned) {
            return this.dimension.equals(dimension) && this.centerX.equals(centerX) && this.centerY.equals(centerY)
                    && this.centerZ.equals(centerZ) && this.radius == radius && this.positioned == positioned;
        }
    }

    /** 一次窗口枚举的产物：窗口内全部键，以及按层分的待发桶。两份在同一次循环里产出。 */
    private record Window(Set<SimulationSectionKey> keys, List<Set<SimulationSectionKey>> buckets) {
    }

    /**
     * 登记一段「内容已定、待下发」。
     *
     * <p>
     * 调用点是 {@link SimulationLifecycle} 的两处：生产收尾与读回完成。它们都在服务端主线程。
     */
    static void noteDirty(ResourceKey<Level> dimension, SimulationSectionKey key) {
        DIRTY.computeIfAbsent(dimension, k -> new HashSet<>()).add(key);
    }

    /** 每 tick 入口，由 {@code FarlandsTick.atEnd} 在窗口驱动之后调用。 */
    public static void tick(MinecraftServer server) {
        Set<UUID> roster = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                roster.add(player.getUUID());
            }
        }
        if (rescanAll || !SENT.keySet().equals(roster)) {
            // 玩家进出：增量路径覆盖不到他们的窗口；进程内首次与清世界状态则要覆盖整张表。
            rescanAll = false;
            for (ServerLevel level : server.getAllLevels()) {
                RESCAN.add(level.dimension());
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level);
        }
        SENT.keySet().retainAll(roster);
        VIEWS.keySet().retainAll(roster);
    }

    private static void tickLevel(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        List<ServerPlayer> players = level.players();
        Object2IntOpenHashMap<SimulationSectionKey> want = WANT.computeIfAbsent(dimension,
                k -> new Object2IntOpenHashMap<>());
        Set<SimulationSectionKey> dirty = DIRTY.computeIfAbsent(dimension, k -> new HashSet<>());

        if (players.isEmpty()) {
            for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
                SimulationLifecycle.release(level, key);
            }
            want.clear();
            dirty.clear();
            VIEWS.values().removeIf(view -> view.dimension().equals(dimension));
            RESCAN.remove(dimension);
            return;
        }

        Map<UUID, List<SimulationSectionKey>> removed = new HashMap<>();
        for (ServerPlayer player : players) {
            ExactPosition exact = (ExactPosition) player;
            Int1024 centerX = exact.farlands$cellCoord1024(ExactPosition.POS_X).shift(-4);
            Int1024 centerY = exact.farlands$cellCoord1024(ExactPosition.POS_Y).shift(-4);
            Int1024 centerZ = exact.farlands$cellCoord1024(ExactPosition.POS_Z).shift(-4);
            ChunkTrackingView tracking = player.getChunkTrackingView();
            boolean positioned = tracking instanceof ChunkTrackingView.Positioned;
            int radius = positioned ? ((ChunkTrackingView.Positioned) tracking).viewDistance() : 8;
            View cached = VIEWS.get(player.getUUID());
            if (cached != null && cached.sameWindow(dimension, centerX, centerY, centerZ, radius, positioned)) {
                continue;
            }
            Set<SimulationSectionKey> sent = farlands$sentOf(player, dimension);
            Window window = farlands$window(centerX, centerY, centerZ, radius, sent);
            List<SimulationSectionKey> out = new ArrayList<>();
            farlands$applyWindowChange(level, player, dimension, want, cached, window.keys(), out);
            removed.put(player.getUUID(), out);
            VIEWS.put(player.getUUID(), new View(dimension, centerX, centerY, centerZ, radius, positioned,
                    window.keys(), window.buckets()));
        }

        if (RESCAN.remove(dimension)) {
            for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
                if (!want.containsKey(key)) {
                    SimulationLifecycle.release(level, key);
                }
            }
        }

        // 内容变更分发给窗口内的各玩家桶：进过桶但没发的键本就在桶里，这里补的是「已发过、内容又变了」那一批，
        // 以及「进窗时非终态、当时被摘掉」那一批。
        if (!dirty.isEmpty()) {
            for (ServerPlayer player : players) {
                View view = VIEWS.get(player.getUUID());
                if (view == null) {
                    continue;
                }
                for (SimulationSectionKey key : dirty) {
                    if (!view.keys().contains(key)) {
                        continue;
                    }
                    view.buckets().get(farlands$layer(key, view.centerX(), view.centerZ())).add(key);
                }
            }
            dirty.clear();
        }

        int[] totals = new int[2];
        for (ServerPlayer player : players) {
            send(level, player, VIEWS.get(player.getUUID()),
                    removed.getOrDefault(player.getUUID(), List.of()), totals);
        }
        farlands$tickReadout(dimension, players.size(), want.size(), totals);
    }

    /**
     * 应用一次窗口变化：先退回离窗的段，再接收进窗的段。
     *
     * <p>
     * 顺序是契约。离窗这一支先裁剪已发集合、再减计数，减到 0 才释放；进窗这一支先加计数、再 ensure。反过来做，会在
     * 「同一个键被两个玩家要、其中一个离开」时把它当成没人要而释放。
     *
     * <p>
     * 换维度时先把旧维度那一份整份退回：段键不含维度，把两个维度的键放进同一张计数表会互相覆盖。
     *
     * @param removed 出参：本 tick 离开窗口的键，供派发通知客户端撤回
     */
    private static void farlands$applyWindowChange(ServerLevel level, ServerPlayer player,
            ResourceKey<Level> dimension, Object2IntOpenHashMap<SimulationSectionKey> want, View cached,
            Set<SimulationSectionKey> next, List<SimulationSectionKey> removed) {
        if (cached != null && !cached.dimension().equals(dimension)) {
            farlands$retractDimension(player, cached);
            cached = null;
        }
        Set<SimulationSectionKey> previous = cached == null ? Set.of() : cached.keys();
        Set<SimulationSectionKey> sent = farlands$sentOf(player, dimension);
        for (SimulationSectionKey key : previous) {
            if (next.contains(key)) {
                continue;
            }
            if (sent.remove(key)) {
                removed.add(key);
            }
            int have = want.getInt(key);
            if (have == 0) {
                continue;
            }
            if (have <= 1) {
                want.removeInt(key);
                SimulationLifecycle.release(level, key);
            } else {
                want.put(key, have - 1);
            }
        }
        for (SimulationSectionKey key : next) {
            if (previous.contains(key)) {
                continue;
            }
            want.addTo(key, 1);
            SimulationLifecycle.ensureSection(level, key);
        }
    }

    /**
     * 换维度时把玩家在旧维度的那一份整份退回。
     *
     * <p>
     * 这里只减计数不释放：拿不到旧维度的 level 实例，而旧维度这一 tick 若已无玩家，它自己的那一支会把整表释放；
     * 若还有别的玩家，减到 0 的键由旧维度下一次对账收走，所以这里把旧维度排进对账。
     */
    private static void farlands$retractDimension(ServerPlayer player, View cached) {
        Object2IntOpenHashMap<SimulationSectionKey> want = WANT.get(cached.dimension());
        if (want != null) {
            for (SimulationSectionKey key : cached.keys()) {
                int have = want.getInt(key);
                if (have == 0) {
                    continue;
                }
                if (have <= 1) {
                    want.removeInt(key);
                } else {
                    want.put(key, have - 1);
                }
            }
        }
        Map<ResourceKey<Level>, Set<SimulationSectionKey>> perDimension = SENT.get(player.getUUID());
        if (perDimension != null) {
            perDimension.remove(cached.dimension());
        }
        RESCAN.add(cached.dimension());
    }

    /** 该玩家在该维度上已经发过的段。该维度之外的已发集合整份作废。 */
    private static Set<SimulationSectionKey> farlands$sentOf(ServerPlayer player, ResourceKey<Level> dimension) {
        Map<ResourceKey<Level>, Set<SimulationSectionKey>> perDimension = SENT.computeIfAbsent(player.getUUID(),
                k -> new HashMap<>());
        perDimension.keySet().removeIf(key -> !key.equals(dimension));
        return perDimension.computeIfAbsent(dimension, k -> new HashSet<>());
    }

    /**
     * 该玩家视距内的模拟区域：横向按圆盘，纵向取整窗，再与可玩段界取交。键集与层桶在同一次循环里产出。
     *
     * <p>
     * 窗口是玩家窗口与模拟区域的交，不是玩家窗口本身。三轴都在可玩段界之内的段归地形侧，这里既不建也不发；归属
     * 按段判，而可玩界与可玩段界逐格对齐，所以不存在半覆盖段，逐键判归属不会把一段切成两半。
     *
     * <p>
     * 竖直方向不另设层距。视距内每一段都必须存在，空腔才有对象承载光：曾经按每 6 段一层枚举，段与段之间的空腔
     * 没有对象，任何跨出段边界的取光都落到「无段」上得到 0，表现为段底发黑。层距如今不再是存在性口径，哪些段
     * 有方块完全由选中的系统决定。
     *
     * <p>
     * 窗口不外扩：边缘的段都是空的，没有面可黑；圆盘边缘朝外的面背向观察者。
     *
     * <p>
     * 三个轴的取值先算成数组，键只引用数组元素，于是每个键只有一次分配。层号由这一圈的 dx 与 dz 直接给出，不必从
     * 键反算；归属判在造键之前，界内的段连键都不建。
     */
    private static Window farlands$window(Int1024 centerX, Int1024 centerY, Int1024 centerZ, int radius,
            Set<SimulationSectionKey> sent) {
        int half = FarlandsConfig.verticalSimulationDistance;
        long radiusSqr = (long) radius * radius;
        Int1024[] xs = new Int1024[2 * radius + 1];
        Int1024[] zs = new Int1024[2 * radius + 1];
        Int1024[] ys = new Int1024[2 * half + 1];
        for (int index = 0; index < xs.length; index++) {
            Int1024 offset = Int1024.of((long) (index - radius));
            xs[index] = centerX.add(offset);
            zs[index] = centerZ.add(offset);
        }
        for (int index = 0; index < ys.length; index++) {
            ys[index] = centerY.add(Int1024.of((long) (index - half)));
        }
        Set<SimulationSectionKey> keys = new HashSet<>();
        List<Set<SimulationSectionKey>> buckets = new ArrayList<>(radius + 1);
        for (int layer = 0; layer <= radius; layer++) {
            buckets.add(new HashSet<>());
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if ((long) dx * dx + (long) dz * dz > radiusSqr) {
                    continue;
                }
                int layer = Math.max(Math.abs(dx), Math.abs(dz));
                Int1024 x = xs[dx + radius];
                Int1024 z = zs[dz + radius];
                for (Int1024 y : ys) {
                    if (!farlands$owned(x, y, z)) {
                        continue;
                    }
                    SimulationSectionKey key = new SimulationSectionKey(x, y, z);
                    keys.add(key);
                    if (!sent.contains(key)) {
                        buckets.get(layer).add(key);
                    }
                }
            }
        }
        return new Window(keys, buckets);
    }

    /**
     * 该段是否归模拟层：三轴中任一轴的段号在可玩段界之外。
     *
     * <p>
     * 与地形侧 {@code Scheduler.sectionsFor} 的剔段判据互为补集：那里用 {@code WorldBounds.inSection} 剔掉界外
     * 段，这里用它的补集剔掉界内段。判据收在 {@code WorldBounds} 一处，三轴写法不在调用点重写。
     */
    private static boolean farlands$owned(Int1024 x, Int1024 y, Int1024 z) {
        return !WorldBounds.inSection(x, y, z);
    }

    /**
     * 对单个玩家派发一批：从最内层桶往外取，取到字节预算满为止。
     *
     * <p>
     * 候选只有一处来源：该玩家窗口的层桶。桶里是「窗口内、尚未发给该玩家」的段，窗口变化时整份重建，平时只有摘除。
     * 所以本方法每 tick 的工作量是「取到预算满」那一小批，与窗口大小无关。
     *
     * <p>
     * 非终态的段当场从桶里摘掉而不留：它升到终态时由 {@code SimulationLifecycle} 经 {@link #noteDirty} 登记，那一笔
     * 会把它重新放进桶。留在桶里只会让每一 tick 白看它一遍。
     *
     * <p>
     * 迭代中不改集合：先把要摘的键收进两张表，循环结束后按层号回摘。
     */
    private static void send(ServerLevel level, ServerPlayer player, View view, List<SimulationSectionKey> gone,
            int[] totals) {
        ResourceKey<Level> dimension = level.dimension();
        Set<SimulationSectionKey> sent = farlands$sentOf(player, dimension);
        farlands$removalReadout(dimension, view, gone);

        int budget = FarlandsConfig.sectionSendBytesPerTick;
        int used = 0;
        boolean full = false;
        List<SimulationSectionPacket.SectionEntry> loaded = new ArrayList<>();
        List<SimulationSectionKey> taken = new ArrayList<>();
        List<SimulationSectionKey> dropped = new ArrayList<>();
        for (Set<SimulationSectionKey> bucket : view.buckets()) {
            for (SimulationSectionKey key : bucket) {
                SimulationSectionData data = SimulationStore.get(dimension, key);
                // 未建或内容未定：此刻发出去只能是空壳，留到它升到 LIGHTED 之后的某一 tick。
                if (data == null || !SimulationSectionStage.isTerminal(data.stage())) {
                    dropped.add(key);
                    continue;
                }
                SimulationSectionPacket.SectionEntry entry = SimulationSectionPacket.encode(key, data);
                int size = SimulationSectionPacket.encodedSize(entry);
                if (used > 0 && used + size > budget) {
                    full = true;
                    break;
                }
                loaded.add(entry);
                used += size;
                taken.add(key);
            }
            if (full) {
                break;
            }
        }
        for (SimulationSectionKey key : taken) {
            view.buckets().get(farlands$layer(key, view.centerX(), view.centerZ())).remove(key);
            sent.add(key);
        }
        for (SimulationSectionKey key : dropped) {
            view.buckets().get(farlands$layer(key, view.centerX(), view.centerZ())).remove(key);
        }
        totals[0] += loaded.size();
        totals[1] += gone.size();
        if (loaded.isEmpty() && gone.isEmpty()) {
            return;
        }
        player.connection.send(new ClientboundCustomPayloadPacket(
                new SimulationSectionPacket(dimension, loaded, gone)));
    }

    /**
     * 段键的层号：水平切比雪夫距离。
     *
     * <p>
     * 调用点只传本窗口内的键，水平差因此不超过 radius，落回 long 精确。
     */
    private static int farlands$layer(SimulationSectionKey key, Int1024 centerX, Int1024 centerZ) {
        long dx = Math.abs(key.x().subtract(centerX).toLong());
        long dz = Math.abs(key.z().subtract(centerZ).toLong());
        return (int) Math.max(dx, dz);
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
        Set<Int1024> ys = new HashSet<>();
        int rmin = Integer.MAX_VALUE;
        int rmax = 0;
        for (SimulationSectionKey key : removed) {
            Int1024 dx = Int1024.abs(key.x().subtract(view.centerX()));
            Int1024 dz = Int1024.abs(key.z().subtract(view.centerZ()));
            Int1024 span = dx.compareTo(dz) >= 0 ? dx : dz;
            // 换维度或传送时离窗的键可能远在 long 之外，这里统一按 int 上限记，本方法只关心「贴中心」这一档。
            int distance = span.bitLength() <= 31 ? span.toInt() : Integer.MAX_VALUE;
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

    /** 停服清空已发记录、窗口缓存、计数表、待分发集合与对账标志。 */
    public static void clearWorldState() {
        SENT.clear();
        VIEWS.clear();
        WANT.clear();
        DIRTY.clear();
        RESCAN.clear();
        rescanAll = true;
        LAST_TICK.clear();
    }
}
