package com.inf.farlands.util.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.network.expand.y.LightUpdatePacket;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.serialize.TerrainHooks;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import io.netty.buffer.Unpooled;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 窗口滑动 section 包的发送端。
 *
 * <p>
 * 服务端主线程每 tick：窗口差量检测；限量发包。
 *
 * <p>
 * 窗口 = 玩家 sectionY ± {@link FarlandsConfig#verticalSimulationDistance}，
 * 与 axisY 的窗口、实体窗口、随机刻窗口同源。section 数据以 byte[] 中转，是否发送由接收端按包内
 * 绝对 sectionY 与 chunk 当前状态决定。
 *
 * <p>
 * 内容变化由 {@link #markChunkChanged} 只记 chunk，给谁由每 tick 在 flush 之前按玩家当前窗口物化
 * 决定，因此事件发生时没有玩家在范围内也不会漏发。标记会被生成线程调用，而 flush 在主线程迭代
 * 同一批集合，故外层 map 用 ConcurrentHashMap、内层集合用并发集合，两边都不需要额外加锁。
 */
public final class ChunkDataSender {

    private ChunkDataSender() {
    }

    /** per-player 窗口状态。 */
    private static final class PlayerWindowState {
        int centerY;
        ResourceKey<Level> dimension;
        /** 上次 XZ chunk 坐标，动态优先级触发检测用。 */
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        PlayerWindowState(int centerY, ResourceKey<Level> dimension) {
            this.centerY = centerY;
            this.dimension = dimension;
        }
    }

    /** UUID 到上次窗口中心与维度。维度变化按首次记录语义处理，不发 difference。 */
    private static final Map<UUID, PlayerWindowState> WINDOW_STATES = new ConcurrentHashMap<>();

    /** 队列：UUID 到 chunkPos 到新进入的 sectionY 集合。已发送即出队，队列无历史状态机。 */
    private static final Map<UUID, Map<Long, Set<Integer>>> PENDING_QUEUES = new ConcurrentHashMap<>();

    /** 内容变化过的 chunk，按维度分桶。给谁由每 tick 物化决定，见 {@link #markChunkChanged}。 */
    private static final Map<ResourceKey<Level>, Set<Long>> CHANGED_CHUNKS = new ConcurrentHashMap<>();

    /** 玩家退出清理，防状态随会话永存。 */
    public static void onPlayerLogout(UUID id) {
        WINDOW_STATES.remove(id);
        PENDING_QUEUES.remove(id);
    }

    /** 停服时清掉按玩家与按维度的表。两个队列表里持有的都是旧 LevelChunk。 */
    public static void clearWorldState() {
        WINDOW_STATES.clear();
        PENDING_QUEUES.clear();
        CHANGED_CHUNKS.clear();
        playersMoved = false;
    }

    /** 本 tick 是否有玩家跨 chunk 移动，驱动地形与光照队列按当前距离重排。 */
    private static volatile boolean playersMoved;

    /** 取走并清除玩家移动标志。 */
    public static boolean consumePlayersMoved() {
        boolean moved = playersMoved;
        playersMoved = false;
        return moved;
    }

    /**
     * 服务端每 tick 入口：窗口差量加限量发包。
     * 返回 true 表示任一玩家窗口发生变化，调用方 FarlandsTick 据此驱动 fsa 清理判定。
     */
    public static boolean tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        boolean windowChanged = false;
        playersMoved = false;
        Map<ResourceKey<Level>, Set<Long>> changed = takeChangedChunks();
        for (ServerPlayer player : players) {
            if (updatePlayerWindow(player)) {
                windowChanged = true;
            }
        }
        for (ServerPlayer player : players) {
            materializeChanged(player, changed);
        }
        for (ServerPlayer player : players) {
            flushPendingSections(player);
        }
        // 玩家退出后清理窗口状态，roster 变化在下一次 tick 体现
        Set<UUID> roster = new HashSet<>();
        for (ServerPlayer p : players) {
            roster.add(p.getUUID());
        }
        WINDOW_STATES.keySet().retainAll(roster);
        PENDING_QUEUES.keySet().retainAll(roster);
        return windowChanged;
    }

    /**
     * 玩家窗口下界与上界，界夹到可表示段范围。
     *
     * <p>不夹的后果：玩家段 134217726 时上界为 134217743，越过可表示上界 134217727。
     * 逐段 enqueueForWindow 会经 BiomeFiller 落到 ChunkAccess.getSection，把该段建进
     * allSections，那里没有边界判定；随后经本类的 section 包送到客户端，客户端按
     * allSections 的键跨度建渲染数组，跨度可达上亿，每次调用分配 GB 级引用数组，
     * 表现为周期性 GC 停顿。
     *
     * <p>先 long 化再夹：centerY 接近 int 顶端时 centerY + N 自身会回绕，回绕值再取 min
     * 得到的是错误的上界。
     */
    static int windowMinSection(int centerY) {
        return (int) Math.max((long) centerY - FarlandsConfig.verticalSimulationDistance,
                (long) WorldBounds.MIN_SECTION);
    }

    static int windowMaxSection(int centerY) {
        return (int) Math.min((long) centerY + FarlandsConfig.verticalSimulationDistance,
                (long) WorldBounds.MAX_SECTION);
    }

    /**
     * 窗口变化检测与 difference：新窗口内不在旧窗口区间的 sectionY 即新进入，入队该玩家 tracking
     * view 内每个 chunk。首次记录或换维度时整窗入队。
     *
     * <p>
     * 返回 true 表示窗口变化。
     */
    public static boolean updatePlayerWindow(ServerPlayer player) {
        ResourceKey<Level> dim = player.level().dimension();
        UUID id = player.getUUID();
        int centerY = Mth.floorDiv(player.getBlockY(), 16);
        PlayerWindowState state = WINDOW_STATES.get(id);

        if (state == null || !state.dimension.equals(dim)) {
            // 换维度或首次：键里没有维度，旧维度的同坐标条目必须先清掉，否则会被当成新维度的待发项。
            PENDING_QUEUES.remove(id);
            WINDOW_STATES.put(id, new PlayerWindowState(centerY, dim));
            int min = windowMinSection(centerY);
            int max = windowMaxSection(centerY);
            for (int sy = min; sy <= max; sy++) {
                enqueueForWindow(player, sy);
            }
            return true;
        }
        // 任一玩家跨 chunk 移动时，地形与光照队列按当前距离重排。
        ChunkPos pc = player.chunkPosition();
        if (state.lastChunkX != pc.x() || state.lastChunkZ != pc.z()) {
            state.lastChunkX = pc.x();
            state.lastChunkZ = pc.z();
            playersMoved = true;
        }
        if (state.centerY == centerY) {
            return false;
        }
        int oldMin = windowMinSection(state.centerY);
        int oldMax = windowMaxSection(state.centerY);
        int newMin = windowMinSection(centerY);
        int newMax = windowMaxSection(centerY);
        if (newMin > newMax) {
            return false;
        }
        for (int sy = newMin; sy <= newMax; sy++) {
            if (sy >= oldMin && sy <= oldMax) {
                continue;
            }
            enqueueForWindow(player, sy);
        }
        state.centerY = centerY;
        return true;
    }

    /**
     * 该 sectionY 入队到玩家 tracking view 内每个 chunk，并对已加载的 chunk 触发 fsa 读回。
     *
     * <p>
     * fsa 读回要在磁盘有数据时恢复 section、光照与 stage，并补发 section 包，它与这里的发送复用
     * 同一次窗口差量检测。只对 ChunkStatus.FULL 的 chunk 读回。
     */
    private static void enqueueForWindow(ServerPlayer player, int sectionY) {
        int s = sectionY;
        ServerLevel level = player.level();
        player.getChunkTrackingView().forEach(cp -> {
            PENDING_QUEUES
                    .computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(cp.pack(), k -> ConcurrentHashMap.newKeySet())
                    .add(s);
            LevelChunk lc = SectionLifecycle.latestChunk(level, cp.x(), cp.z());
            if (lc != null) {
                // fsa 读回优先：磁盘有数据则恢复数据、光照与 stage 并补发，不重生成。读回完成后
                // 再补填 biome 并入生成队列，GenQueue.enqueue 的 isOrAfter 会跳过已读回的 section。
                // 盘上无数据时这条回调在调用线程即主线程上内联跑。这里不补填群系：那会在主线程上做一次
                // 64 次气候采样的填充，而下一句入队带来的 GenTask 本来就会「先补 biome 再推 TERRAIN」，
                // 段要到 LIGHTED 才下发，所以下发路径不受影响。
                SectionLifecycle.loadSection(lc, s, () -> TerrainHooks.enqueueGen(lc, s));
            }
        });
    }

    /**
     * 标记该 chunk 的内容已变，需要补发。fill 完成、fsa 读回完成、以及 chunk 包发出时为空三种情形
     * 都调它。
     *
     * <p>
     * 只记 chunk，不记玩家：给谁由每 tick 在 flush 之前按玩家当前窗口物化，所以事件发生时没有玩家
     * 在范围内也不会漏发。写入与 {@link #takeChangedChunks} 的换出都走同一个 key 的 compute，由 CHM
     * 逐桶串行，晚到的标记要么落进本 tick 的批次，要么落进换出的新集合等下一 tick。
     *
     * <p>
     * 生成线程与主线程都会调，故标记集合用并发集合。
     */
    public static void markChunkChanged(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long chunkKey = chunk.getPos().pack();
        CHANGED_CHUNKS.compute(level.dimension(), (dimension, marked) -> {
            Set<Long> target = marked == null ? ConcurrentHashMap.newKeySet() : marked;
            target.add(chunkKey);
            return target;
        });
    }

    /** 换出本 tick 的标记批次，每个维度换成新集合。 */
    private static Map<ResourceKey<Level>, Set<Long>> takeChangedChunks() {
        Map<ResourceKey<Level>, Set<Long>> batch = new HashMap<>();
        for (ResourceKey<Level> dimension : new ArrayList<>(CHANGED_CHUNKS.keySet())) {
            CHANGED_CHUNKS.computeIfPresent(dimension, (key, marked) -> {
                batch.put(key, marked);
                return ConcurrentHashMap.newKeySet();
            });
        }
        return batch;
    }

    /**
     * 把本 tick 的内容变化按该玩家的当前窗口物化成 per-player 条目。
     *
     * <p>
     * 只按段的键取，不读段内容：内容判定留在 flush 的 {@code isChunkBusy} 门之后，生成线程正在写
     * section 时读 {@code hasOnlyAir} 会撞 vanilla 的 PalettedContainer 多线程检测。
     */
    private static void materializeChanged(ServerPlayer player, Map<ResourceKey<Level>, Set<Long>> batch) {
        Set<Long> marked = batch.get(player.level().dimension());
        if (marked == null || marked.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        ChunkPos playerChunk = player.chunkPosition();
        ChunkTrackingView view = player.getChunkTrackingView();
        int viewDistance = view instanceof ChunkTrackingView.Positioned pos ? pos.viewDistance() : 8;
        int centerY = Mth.floorDiv(player.getBlockY(), 16);
        int windowMinY = windowMinSection(centerY);
        int windowMaxY = windowMaxSection(centerY);
        for (long chunkKey : marked) {
            int cx = ChunkPos.getX(chunkKey);
            int cz = ChunkPos.getZ(chunkKey);
            if (!ChunkTrackingView.isWithinDistance(playerChunk.x(), playerChunk.z(), viewDistance, cx, cz, true)) {
                continue;
            }
            LevelChunk lc = SectionLifecycle.latestChunk(level, cx, cz);
            if (lc == null) {
                // 未加载：到此为止，重载时 fsa 读回、新生成或空包会重新标记
                continue;
            }
            Set<Integer> sections = PENDING_QUEUES
                    .computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet());
            for (Integer sy : ((WindowedChunk) lc).windowedAllSections().keySet()) {
                if (sy >= windowMinY && sy <= windowMaxY) {
                    sections.add(sy);
                }
            }
        }
    }

    /** 限量发送：按距离排序出队，累计不超过 sectionSendBytesPerTick，打包为一个段包。 */
    private static void flushPendingSections(ServerPlayer player) {
        Map<Long, Set<Integer>> queue = PENDING_QUEUES.get(player.getUUID());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        ChunkPos playerChunk = player.chunkPosition();
        int budget = FarlandsConfig.sectionSendBytesPerTick;
        int windowMinY = windowMinSection(Mth.floorDiv(player.getBlockY(), 16));

        List<Map.Entry<Long, Set<Integer>>> sorted = new ArrayList<>(queue.entrySet());
        sorted.sort(Comparator.comparingLong(e -> distSq(e.getKey(), playerChunk)));

        List<ChunkDataPacket.SectionEntry> batch = new ArrayList<>();
        int used = 0;
        outer: for (Map.Entry<Long, Set<Integer>> e : sorted) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            // 不在跟踪面：剪掉，否则队列随探索单调增长；换维度后残留的同坐标条目也在这一句里被剪。
            if (!player.getChunkTrackingView().contains(new ChunkPos(cx, cz))) {
                queue.remove(e.getKey());
                continue;
            }
            LevelChunk lc = SectionLifecycle.latestChunk(level, cx, cz);
            if (lc == null) {
                queue.remove(e.getKey()); // 未加载：剪掉，重载时标记或 chunk 包会补
                continue;
            }
            if (GenQueue.isGeneratingOrLighting(lc)) {
                // 生成与光照在途：整块留队列，不读半成品 section。它们的段写不取包锁，序列化会撞
                // vanilla 的 PalettedContainer 多线程检测，直接 CTD。装饰的段写取那把锁，不必让开。
                continue;
            }
            Iterator<Integer> it = e.getValue().iterator();
            while (it.hasNext()) {
                int sy = it.next();
                LevelChunkSection section = ((WindowedChunk) lc).windowedAllSections().get(sy);
                if (section == null) {
                    it.remove(); // 段不存在：剪掉
                    continue;
                }
                // 与落盘同门：未点亮的段是中间态，不发，留队列；它升到 LIGHTED 时由 GenQueue.triggerLight
                // 的完成回调补一次内容变化标记，那时这一笔才出队。
                if (!com.inf.farlands.serialize.SectionStage.isOrAfter(lc, sy,
                        com.inf.farlands.serialize.SectionStage.LIGHTED)) {
                    continue;
                }
                // 纯空气段照发：群系存在段里，不发客户端就没有那份群系，读到的是工厂默认群系
                if (GenQueue.isLightInFlight(lc) && ((WindowedChunk) lc).isSectionDirty(sy)) {
                    // 生成 fill 的 section 光照播种未完成，留队列等光照。读回的 section 未脏，放行发送。
                    continue;
                }
                FriendlyByteBuf tmp = new FriendlyByteBuf(Unpooled.buffer());
                // 与编码池的 fsa 编码互斥：同一批段可能正被池打包，两个线程同时进 PalettedContainer 会抛。
                synchronized (SectionSerializer.packLockFor(lc.getPos().pack())) {
                    section.write(tmp);
                }
                byte[] data = new byte[tmp.readableBytes()];
                tmp.readBytes(data);
                int est = 24 + data.length;
                if (used + est > budget) {
                    break outer; // 预算耗尽，剩余留队列
                }
                SectionPos spos = SectionPos.of(lc.getPos(), sy);
                LevelLightEngine le = level.getChunkSource().getLightEngine();
                DataLayer bl = le.getLayerListener(LightLayer.BLOCK).getDataLayerData(spos);
                DataLayer sl = le.getLayerListener(LightLayer.SKY).getDataLayerData(spos);
                batch.add(new ChunkDataPacket.SectionEntry(
                        cx, cz, sy, data,
                        ChunkDataPacket.encodeLight(bl),
                        ChunkDataPacket.encodeLight(sl)));
                it.remove();
                used += est;
            }
            if (e.getValue().isEmpty()) {
                queue.remove(e.getKey());
            }
        }
        if (!batch.isEmpty()) {
            player.connection.send(new ClientboundCustomPayloadPacket(
                    new ChunkDataPacket(level.dimension(), windowMinY, batch)));
        }
    }

    private static long distSq(long chunkKey, ChunkPos playerChunk) {
        long dx = (long) ChunkPos.getX(chunkKey) - playerChunk.x();
        long dz = (long) ChunkPos.getZ(chunkKey) - playerChunk.z();
        return dx * dx + dz * dz;
    }

    /**
     * 播种完成后主动广播该 chunk 全部光照，含空 section 的 15。
     *
     * <p>
     * 增量包链依赖 chunk 达 ENTITY_TICKING，而空壳先发加光照后补的管线里播种时往往未达，
     * 客户端会保持邻居传播写入的渐黑。本方法绕开该依赖，播种完成即对 tracking 玩家发全窗口光照。
     */
    public static void broadcastChunkLight(ServerLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.getChunkTrackingView().contains(cp)) {
                players.add(p);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        LevelLightEngine le = level.getChunkSource().getLightEngine();
        List<LightUpdatePacket.SectionLight> sky = new ArrayList<>();
        List<LightUpdatePacket.SectionLight> block = new ArrayList<>();
        for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            SectionPos spos = SectionPos.of(cp, sy);
            sky.add(new LightUpdatePacket.SectionLight(sy,
                    LightUpdatePacket.encodeSectionLight(
                            le.getLayerListener(LightLayer.SKY).getDataLayerData(spos))));
            block.add(new LightUpdatePacket.SectionLight(sy,
                    LightUpdatePacket.encodeSectionLight(
                            le.getLayerListener(LightLayer.BLOCK).getDataLayerData(spos))));
        }
        LightUpdatePacket pkt = new LightUpdatePacket(level.dimension(), cp.x(), cp.z(), sky, block);
        for (ServerPlayer p : players) {
            p.connection.send(new ClientboundCustomPayloadPacket(pkt));
        }
    }
}
