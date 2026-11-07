package com.inf.farlands.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * 服务端的模拟层驱动：窗口变化时增删段，其余 tick 只派发脏段。
 *
 * <p>
 * 生命周期照原版的 chunk：视距并集里没有的段释放，新进的段创建并跑一次生产者，首次发送整段，之后只发脏段。
 * 与 chunk 的差别只有一条：这里不持久化，段释放即丢。
 *
 * <p>
 * 每 tick 的工作量与窗口变化量成正比，不与窗口大小成正比。旧写法每 tick 重建 11 万个键的窗口、重算并集、扫全表
 * 找该释放的段，在视距 32 下稳定是五十毫秒级的开销，与玩家是否移动无关。现在窗口按玩家缓存，只有中心段、视距或
 * 维度变了才重建；并集是按维度增量维护的计数表，「谁想要某个键」与「并集」因此是同一份信息。
 *
 * <p>
 * 已发集合与计数表都按维度分表：段键只有三轴段号，不含维度，跨维度同坐标会互相覆盖。
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
     * 它同时就是并集：并集是计数大于 0 的键。旧写法每 tick 重建这张表，现在只在窗口变化时增减。
     */
    private static final Map<ResourceKey<Level>, Map<SimulationSectionKey, Integer>> WANT = new HashMap<>();

    /**
     * 按维度的「待下发」集合。
     *
     * <p>
     * 内容是「还没发全的段」，来源有三处：生产与读回完成时的标脏，见 {@link #noteDirty}；窗口新进的段；以及本 tick
     * 因字节预算没发完的候选。每 tick 的候选从它里面取，不再扫全窗。
     *
     * <p>
     * 段的键只有三轴段号，所以这张表也按维度分。
     */
    private static final Map<ResourceKey<Level>, Set<SimulationSectionKey>> DIRTY = new HashMap<>();

    /**
     * 下一个 tick 要不要做一次全表对账。
     *
     * <p>
     * 增量路径只处理窗口变化的那批键，覆盖不到两类残留：上一个世界留下的表，以及落盘提交未完成、条目还留在表里的
     * 键。所以进程内首次、清世界状态、玩家集合变化、换维度时各置一次。
     */
    private static boolean rescan = true;

    /** 临时读数，用完删除：上一 tick 的并集大小与存储大小。 */
    private static final Map<ResourceKey<Level>, int[]> LAST_TICK = new HashMap<>();

    /**
     * 临时读数，结案即删：一个间隔内的累计量与最近一次的存量，全在服务端主线程读写。
     */
    private static int probeSendNew;

    private static int probeSendResent;

    private static int probeClearDirty;

    private static int probeStore;

    private static int probeUnion;

    private static int probeSent;

    private static int probeSamples;

    private static int probeTicks;

    private static void farlands$reportProbe() {
        int[] core = SimulationLifecycle.takeProbe();
        InfsFarlands.LOGGER.info(
                "FLSIM2 store={} union={} sent={} ensureHit={} ensureMiss={} produce={} readback={} release={} sendNew={} sendResent={} clearDirty={}",
                probeStore, probeUnion, probeSent, core[0], core[1], core[2], core[3], core[4], probeSendNew,
                probeSendResent, probeClearDirty);
        probeSendNew = 0;
        probeSendResent = 0;
        probeClearDirty = 0;
        probeSamples = 0;
    }

    /** 临时读数，结案即删：每个间隔最多 8 条被重发的键，带档位与脏位。 */
    private static void farlands$resentSample(SimulationSectionKey key, SimulationSectionData data) {
        if (probeSamples >= 8) {
            return;
        }
        probeSamples++;
        InfsFarlands.LOGGER.info("FLSIM2 resent key={},{},{} stage={} dirty={}", key.x(), key.y(), key.z(),
                data.stage(), data.isDirty());
    }

    private SimulationServer() {
    }

    /** 一个玩家的窗口：中心三轴、视距与它的键集。 */
    private record View(ResourceKey<Level> dimension, long centerX, long centerY, long centerZ, int radius,
            boolean positioned, Set<SimulationSectionKey> keys) {

        /** 缓存是否仍然对应当前这一刻的窗口。 */
        boolean sameWindow(ResourceKey<Level> dimension, long centerX, long centerY, long centerZ, int radius,
                boolean positioned) {
            return this.dimension.equals(dimension) && this.centerX == centerX && this.centerY == centerY
                    && this.centerZ == centerZ && this.radius == radius && this.positioned == positioned;
        }
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
            tickLevel(level, roster);
        }
        if (!SENT.keySet().equals(roster)) {
            // 玩家进出：增量路径覆盖不到他们的窗口，下一个 tick 对一次全表
            rescan = true;
        }
        SENT.keySet().retainAll(roster);
        VIEWS.keySet().retainAll(roster);
        if (++probeTicks % 200 == 0) {
            farlands$reportProbe();
        }
    }

    private static void tickLevel(ServerLevel level, Set<UUID> roster) {
        ResourceKey<Level> dimension = level.dimension();
        List<ServerPlayer> players = level.players();
        Map<SimulationSectionKey, Integer> want = WANT.computeIfAbsent(dimension, k -> new HashMap<>());
        Set<SimulationSectionKey> dirty = DIRTY.computeIfAbsent(dimension, k -> new HashSet<>());

        if (players.isEmpty()) {
            for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
                SimulationLifecycle.release(level, key);
            }
            want.clear();
            dirty.clear();
            VIEWS.values().removeIf(view -> view.dimension().equals(dimension));
            probeStore = SimulationStore.size(dimension);
            probeUnion = 0;
            return;
        }

        Map<UUID, List<SimulationSectionKey>> removed = new HashMap<>();
        Map<UUID, List<SimulationSectionKey>> arrived = new HashMap<>();
        for (ServerPlayer player : players) {
            roster.add(player.getUUID());
            ExactPosition exact = (ExactPosition) player;
            long centerX = exact.farlands$cellCoord(ExactPosition.POS_X) >> 4;
            long centerY = exact.farlands$cellCoord(ExactPosition.POS_Y) >> 4;
            long centerZ = exact.farlands$cellCoord(ExactPosition.POS_Z) >> 4;
            ChunkTrackingView tracking = player.getChunkTrackingView();
            boolean positioned = tracking instanceof ChunkTrackingView.Positioned;
            int radius = positioned ? ((ChunkTrackingView.Positioned) tracking).viewDistance() : 8;
            View cached = VIEWS.get(player.getUUID());
            if (cached != null && cached.sameWindow(dimension, centerX, centerY, centerZ, radius, positioned)) {
                continue;
            }
            Set<SimulationSectionKey> keys = farlands$keys(centerX, centerY, centerZ, radius);
            List<SimulationSectionKey> in = new ArrayList<>();
            List<SimulationSectionKey> out = new ArrayList<>();
            farlands$applyWindowChange(level, player, dimension, want, dirty, cached, keys, in, out);
            arrived.put(player.getUUID(), in);
            removed.put(player.getUUID(), out);
            VIEWS.put(player.getUUID(), new View(dimension, centerX, centerY, centerZ, radius, positioned, keys));
        }

        if (rescan) {
            rescan = false;
            for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
                if (!want.containsKey(key)) {
                    SimulationLifecycle.release(level, key);
                    dirty.remove(key);
                }
            }
        }
        probeStore = SimulationStore.size(dimension);
        probeUnion = want.size();

        int[] totals = new int[2];
        Map<SimulationSectionKey, Integer> queued = new HashMap<>();
        for (ServerPlayer player : players) {
            View view = VIEWS.get(player.getUUID());
            List<SimulationSectionKey> gone = removed.getOrDefault(player.getUUID(), List.of());
            List<SimulationSectionKey> in = arrived.getOrDefault(player.getUUID(), List.of());
            send(level, player, view, in, dirty, gone, queued, totals);
        }
        // 脏标记只在「所有想要它的玩家这一 tick 都收到了」时才清，否则留在下一 tick 补发。
        for (Map.Entry<SimulationSectionKey, Integer> entry : queued.entrySet()) {
            SimulationSectionData data = SimulationStore.get(dimension, entry.getKey());
            if (data != null && entry.getValue() >= want.getOrDefault(entry.getKey(), 0)) {
                data.clearDirty();
                dirty.remove(entry.getKey());
                probeClearDirty++;
            }
        }
        // 临时读数，用完删除。
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
     * @param arrived 出参：本 tick 进入窗口的键，供派发做首次发送
     * @param removed 出参：本 tick 离开窗口的键，供派发通知客户端撤回
     */
    private static void farlands$applyWindowChange(ServerLevel level, ServerPlayer player,
            ResourceKey<Level> dimension, Map<SimulationSectionKey, Integer> want,
            Set<SimulationSectionKey> dirty, View cached, Set<SimulationSectionKey> next,
            List<SimulationSectionKey> arrived, List<SimulationSectionKey> removed) {
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
            Integer have = want.get(key);
            if (have == null) {
                continue;
            }
            if (have <= 1) {
                want.remove(key);
                dirty.remove(key);
                SimulationLifecycle.release(level, key);
            } else {
                want.put(key, have - 1);
            }
        }
        for (SimulationSectionKey key : next) {
            if (previous.contains(key)) {
                continue;
            }
            want.merge(key, 1, Integer::sum);
            arrived.add(key);
            SimulationLifecycle.ensureSection(level, key);
        }
    }

    /**
     * 换维度时把玩家在旧维度的那一份整份退回。
     *
     * <p>
     * 这里只减计数不释放：拿不到旧维度的 level 实例，而旧维度这一 tick 若已无玩家，它自己的那一支会把整表释放；
     * 若还有别的玩家，减到 0 的键由紧接着的全表对账收走，所以这里置 {@link #rescan}。
     */
    private static void farlands$retractDimension(ServerPlayer player, View cached) {
        Map<SimulationSectionKey, Integer> want = WANT.get(cached.dimension());
        if (want != null) {
            for (SimulationSectionKey key : cached.keys()) {
                Integer have = want.get(key);
                if (have == null) {
                    continue;
                }
                if (have <= 1) {
                    want.remove(key);
                } else {
                    want.put(key, have - 1);
                }
            }
        }
        Map<ResourceKey<Level>, Set<SimulationSectionKey>> perDimension = SENT.get(player.getUUID());
        if (perDimension != null) {
            perDimension.remove(cached.dimension());
        }
        rescan = true;
    }

    /** 该玩家在该维度上已经发过的段。该维度之外的已发集合整份作废。 */
    private static Set<SimulationSectionKey> farlands$sentOf(ServerPlayer player, ResourceKey<Level> dimension) {
        Map<ResourceKey<Level>, Set<SimulationSectionKey>> perDimension = SENT.computeIfAbsent(player.getUUID(),
                k -> new HashMap<>());
        perDimension.keySet().removeIf(key -> !key.equals(dimension));
        return perDimension.computeIfAbsent(dimension, k -> new HashSet<>());
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
    private static Set<SimulationSectionKey> farlands$keys(long centerX, long centerY, long centerZ, int radius) {
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
        return keys;
    }

    /**
     * 对单个玩家做一次差分并限量发送。
     *
     * <p>
     * 候选只来自两处：本 tick 新进窗口的键，与待发集合里属于本玩家窗口的键。旧写法扫全窗，11 万个键每 tick 一遍。
     *
     * <p>
     * 候选先整体登记回待发集合，再由调用方按「所有想要它的玩家都收到了」逐条摘除。这样因字节预算没发完的那批会留在
     * 集合里，下一 tick 接着发，不需要再扫一遍窗口。
     */
    private static void send(ServerLevel level, ServerPlayer player, View view, List<SimulationSectionKey> arrived,
            Set<SimulationSectionKey> dirty, List<SimulationSectionKey> gone,
            Map<SimulationSectionKey, Integer> queued, int[] totals) {
        ResourceKey<Level> dimension = level.dimension();
        Set<SimulationSectionKey> sent = farlands$sentOf(player, dimension);
        farlands$removalReadout(dimension, view, gone);
        probeSent = sent.size();

        Set<SimulationSectionKey> candidateSet = new LinkedHashSet<>();
        for (SimulationSectionKey key : arrived) {
            SimulationSectionData data = SimulationStore.get(dimension, key);
            // 未建或内容未定：此刻发出去只能是空壳，留到它升到 LIGHTED 之后的某一 tick。
            if (data == null || !SimulationSectionStage.isTerminal(data.stage())) {
                continue;
            }
            if (!sent.contains(key)) {
                candidateSet.add(key);
            }
        }
        for (SimulationSectionKey key : dirty) {
            if (!view.keys().contains(key)) {
                continue;
            }
            SimulationSectionData data = SimulationStore.get(dimension, key);
            if (data == null || !SimulationSectionStage.isTerminal(data.stage())) {
                continue;
            }
            candidateSet.add(key);
        }
        List<SimulationSectionKey> candidates = new ArrayList<>(candidateSet);
        candidates.sort(Comparator.comparingLong(key -> distanceSqr(key, view)));
        dirty.addAll(candidateSet);

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
            boolean resent = sent.contains(key);
            loaded.add(SimulationSectionPacket.encode(key, data));
            used += size;
            sent.add(key);
            queued.merge(key, 1, Integer::sum);
            if (resent) {
                probeSendResent++;
                farlands$resentSample(key, data);
            } else {
                probeSendNew++;
            }
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

    /** 停服清空已发记录、窗口缓存、计数表与待发集合，并置全表对账。 */
    public static void clearWorldState() {
        SENT.clear();
        VIEWS.clear();
        WANT.clear();
        DIRTY.clear();
        LAST_TICK.clear();
        rescan = true;
    }
}
