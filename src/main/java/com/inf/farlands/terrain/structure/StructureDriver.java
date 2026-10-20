package com.inf.farlands.terrain.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.decorationFiller.DecorationRegion;
import com.inf.farlands.terrain.decorationFiller.ScopedStructureManager;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 结构两相。驱动自建，方法体调 vanilla 的实现，都在主线程跑。
 *
 * <p>
 * 依赖顺序，三条都是铁的：
 *
 * <ol>
 * <li><b>起点相</b>在 {@link #createStructures}，只读中心自己，写也只写中心自己的
 * {@code structureStarts}。{@code createStructures} 判已有起点，再按
 * {@code generatorState} 决定
 * 该 chunk 落哪个结构集的起点。它不需要任何邻居，所以没有门；跑不跑由地物族的
 * {@code DecorationSystem#createStructures} 决定，跑过的由 {@code STARTED} 记住。</li>
 * <li><b>引用相</b>在 {@link #buildReferences}，读 ±8 的起点表，即 {@code createReferences}
 * 的
 * 17×17 起点网格，写中心自己的引用表。±8 是 vanilla FEATURES 步对 {@code STRUCTURE_STARTS} 的
 * 依赖半径，见 {@code ChunkPyramid}，也是 vanilla 区域允许的读半径。</li>
 * <li><b>Beardifier</b> 读中心的引用表，再按引用取各 chunk 的起点；它必须早于 biome 与 fill。</li>
 * </ol>
 *
 * <p>
 * 缺 ±8 的壳怎么办：按需拉。拉的动作只从 {@code GenTask} 的门里发起，也就是只有真的要 fill 的
 * chunk 才去拉它的 ±8。被拉起来的那些邻居只当壳，算完自己的起点就够，起点相是 chunk 局部的；它们
 * 的 fill 没有任何东西驱动，预加载只走查到 +2，{@code enqueueChunk} 要玩家在附近，所以不会继续
 * 往外拉，一圈即止。若改从存在流程里拉，每个被拉的 chunk 都会再拉自己的 ±8，半径会无界增长，这是
 * 这套设计唯一的陷阱。
 *
 * <p>
 * 由此挂起项分两档，成本也分两档：存在流程登记的是只登记档，即 {@code pinned == null}，不被
 * {@code tick} 扫描，只被 {@code GenTask} 的门读；只有被要 fill 触发、拉过依赖的那档才带续作进扫描。
 * 被拉起来的成片邻居因此只占一个登记项，不产生每 tick 的取数税。
 *
 * <p>
 * 票在 {@code TicketStorage} 里是集合语义、不计数，所以引用计数在这里自己维护，见
 * {@code PIN_REFS}：同一格被多个中心需要时，只在 0 与 1 的跃迁上真的加减 vanilla 票。
 *
 * <p>
 * 为什么结构相在主线程序列：两相写的是 {@code ChunkAccess.structureStarts} 与
 * {@code structuresRefences}，那是 {@code Maps.newHashMap()}，而主线程在读它们，算 Beardifier
 * 与
 * 存盘的 copyOf 都读。票操作同样只在主线程安全。
 */
public final class StructureDriver {

    /** 引用相的读半径：vanilla 的 STRUCTURE_STARTS 依赖半径。 */
    public static final int STRUCTURE_READ_RADIUS = 8;

    /** 起点已算的 chunk，按维度分表，键是 ChunkPos.pack。卸载即清，见 {@link #clearChunk}。 */
    private static final Map<ResourceKey<Level>, Set<Long>> STARTED = new ConcurrentHashMap<>();

    /** 等 ±8 壳齐的 chunk 与它挂起的续作，以及它为本中心补过票的那些格。 */
    private static final Map<Key, Pending> AWAITING = new ConcurrentHashMap<>();

    /** 依赖 pin 的引用计数：维度到 ChunkPos.pack 到需求方数量。票本身不计数，计数记在这里。 */
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> PIN_REFS = new ConcurrentHashMap<>();

    private record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    private record Pending(ServerLevel level, Runnable resume, List<ChunkPos> pinned) {
    }

    private StructureDriver() {
    }

    /**
     * 起点相只跑一次：首次返回真，之后返回假。主线程调用。
     *
     * <p>
     * 自带基座的族与 {@link #createStructures} 共用它，所以「已算过」的判据只有这一处。
     */
    public static boolean claimStructures(ServerLevel level, LevelChunk chunk) {
        Set<Long> started = STARTED.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        return started.add(chunk.getPos().pack());
    }

    /**
     * 起点相：该 chunk 自己的结构起点，用宿主维度的基座。幂等，已算过直接返回。主线程调用。
     *
     * <p>
     * 必须夹在 {@link TerrainSystemContext} 的 set 与 clear 之间：起点取 Y 锚会走
     * {@code getFirstOccupiedHeight}，再到 {@code iterateNoiseColumn}，那里构造
     * {@code NoiseChunk}，
     * 而 {@code NoiseChunkMixin} 的两处 {@code @Redirect} 都取这个侧信道，未设即抛。形状照
     * {@code AbstractTerrainFiller.fill}。
     */
    public static void createStructures(ServerLevel level, LevelChunk chunk) {
        if (!claimStructures(level, chunk)) {
            return;
        }
        TerrainSystemContext.set(((LevelSystems) level).terrainSystem());
        try {
            level.getChunkSource().getGenerator().createStructures(
                    level.registryAccess(),
                    level.getChunkSource().getGeneratorState(),
                    level.structureManager(),
                    chunk,
                    level.getStructureManager(),
                    level.dimension());
        } finally {
            TerrainSystemContext.clear();
        }
    }

    /**
     * 该 level 是否生成结构。为假时起点相、引用相与 ±8 的门全部可跳：那时 {@code createStructures}
     * 什么都不会建、引用表保持为空，而 {@code Beardifier.forStructuresInChunk} 对空起点表返回
     * {@code Beardifier.EMPTY}，它是非空实例，见反编译里那个方法的空表分支。所以跳过与照跑交出的是
     * 同一个对象。
     */
    public static boolean structuresEnabled(ServerLevel level) {
        return level.structureManager().shouldGenerateStructures();
    }

    /**
     * 该 chunk 是否在结构相的可及范围内，即含可玩方块。
     *
     * <p>
     * 可玩界之外的 chunk 在 int 里没有可表示的方块坐标：{@code ChunkPos.getMinBlockX} 与
     * {@code getMinBlockZ} 被本 port 饱和到 {@code Integer.MIN_VALUE + 15} 一侧，结构放置拿这个值推
     * 高度锚，就会有坐标落进 {@code Integer.MIN_VALUE} 上方五格之内，而 vanilla 的网格锚点算式在那里
     * 做 {@code posZ + -5} 的 int 加法，回绕到相反一侧，下标随即越过网格数组。这类 chunk 本来也没有
     * 可玩方块，所以与结构关掉的世界走同一条捷径。
     */
    public static boolean inStructureRange(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return WorldBounds.inChunkRange(pos.x(), pos.z());
    }

    /**
     * 引用相是否可做：中心 ±{@link #STRUCTURE_READ_RADIUS} 的壳都在场。主线程调用。
     *
     * <p>
     * 在场即够：每个壳在存在流程里都已跑过自己的起点相，因为 {@link #createStructures} 由存在流程统一
     * 调一次；旧档的起点由读盘路径填回。所以壳在场蕴含起点表可读。不产结构的族不会走到这里，见
     * {@code DecorationSystem#createStructures} 的返回值。
     */
    public static boolean referencesBuildable(ServerLevel level, LevelChunk center) {
        ChunkPos pos = center.getPos();
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        for (int dx = -STRUCTURE_READ_RADIUS; dx <= STRUCTURE_READ_RADIUS; dx++) {
            for (int dz = -STRUCTURE_READ_RADIUS; dz <= STRUCTURE_READ_RADIUS; dz++) {
                if (cursor.at(pos.x() + dx, pos.z() + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 还缺哪些格的壳。只在 {@link #pullDependencies} 里调，所以允许分配。主线程调用。 */
    private static List<ChunkPos> missingCells(ServerLevel level, LevelChunk center) {
        List<ChunkPos> missing = new ArrayList<>();
        ChunkPos pos = center.getPos();
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        for (int dx = -STRUCTURE_READ_RADIUS; dx <= STRUCTURE_READ_RADIUS; dx++) {
            for (int dz = -STRUCTURE_READ_RADIUS; dz <= STRUCTURE_READ_RADIUS; dz++) {
                if (cursor.at(pos.x() + dx, pos.z() + dz) == null) {
                    missing.add(new ChunkPos(pos.x() + dx, pos.z() + dz));
                }
            }
        }
        return missing;
    }

    /**
     * 按需拉依赖：给缺的格各铺一张 pin 票，并把补过的格记进挂起项。只从 fill 路径发起，由
     * {@code GenTask.execute} 经主线程 marshal 调它，这样只有真的要 fill 的 chunk 才拉，被拉的邻居
     * 不会再拉，一圈即止。主线程调用；同一个中心只拉一次。
     */
    public static void pullDependencies(ServerLevel level, LevelChunk center) {
        Key key = new Key(level.dimension(), center.getPos().pack());
        Pending pending = AWAITING.get(key);
        if (pending == null || pending.pinned() != null) {
            return; // 不是挂起项，或已经拉过
        }
        List<ChunkPos> missing = missingCells(level, center);
        for (ChunkPos pos : missing) {
            pinRef(level, pos);
        }
        AWAITING.put(key, new Pending(level, pending.resume(), List.copyOf(missing)));
        // 提升也是重扫的理由：这些壳可能在被提升之前就到齐了，那时 pinned 仍为 null，tick 扫到也不
        // 处理；此后如果没有新壳出现，tick 就会因为没有新壳一直跳过它们，项永远不推进。
        cellChanged();
    }

    /**
     * 引用相：中心自己的引用表，读 ±8 的起点。主线程调用；调用前必须过
     * {@link #referencesBuildable}。
     */
    public static void buildReferences(ServerLevel level, LevelChunk center) {
        DecorationRegion region = DecorationRegion.readingFrom(level, center, STRUCTURE_READ_RADIUS);
        TerrainSystemContext.set(((LevelSystems) level).terrainSystem());
        try {
            level.getChunkSource().getGenerator().createReferences(
                    region, ScopedStructureManager.of(level, region), center);
        } finally {
            TerrainSystemContext.clear();
        }
    }

    /** 把该 chunk 挂起：等 ±8 的壳齐了再由 {@link #tick} 跑续作。主线程调用。 */
    public static void await(ServerLevel level, LevelChunk chunk, Runnable resume) {
        AWAITING.put(new Key(level.dimension(), chunk.getPos().pack()), new Pending(level, resume, null));
    }

    /**
     * 自上次扫描以来是否有格的壳变过。±8 的就绪性只取决于壳在场这一条单调条件，所以一个全局标志就
     * 够，不需要每项计数：没有新壳，就没有任何一项可能从「不就绪」变成「就绪」。
     *
     * <p>
     * 置位点有两处：{@link #cellChanged} 与 {@link #clearChunk}。只登记未提升的项本来就不扫，
     * 见 {@link #tick} 里的那一段。
     */
    private static volatile boolean shellChanged = true;

    /** 某一格的壳出现或消失。主线程调用。 */
    public static void cellChanged() {
        shellChanged = true;
    }

    /**
     * 每 tick 重试：门过了就跑续作、撤本中心补的票；没过就留下；壳没了就丢项并撤票。主线程调用。
     *
     * <p>
     * 撤票在续作之后，因为引用相正是在续作里读那些格的起点表。
     */
    public static void tick() {
        if (AWAITING.isEmpty() || !shellChanged) {
            return; // 没有新壳：没有任何一项可能刚从「不就绪」变成「就绪」
        }
        shellChanged = false;
        for (Map.Entry<Key, Pending> e : new ArrayList<>(AWAITING.entrySet())) {
            Key key = e.getKey();
            Pending pending = e.getValue();
            LevelChunk chunk = SectionLifecycle.latestChunk(pending.level(), ChunkPos.getX(key.chunkPos()),
                    ChunkPos.getZ(key.chunkPos()));
            if (chunk == null) {
                // 这一刻取不到 chunk 不代表它卸载了：visibleChunkMap 的取数会短暂落空。不能在这里丢项，
                // 因为存在流程不会为一个已存在的 chunk 再跑一遍，丢了就再没人登记它，而它的 Beardifier
                // 永远补不上。症状是该 chunk 永不 fill，且每轮 GenTask 撞门后投一笔主线程任务，drain
                // 空转。权威的移除点是卸载，见 clearChunk。
                continue;
            }
            if (pending.pinned() == null) {
                // 只登记、还没被要 fill 触发过，只有 pullDependencies 才会填 pinned：不扫也不跑。
                // 扫一次是 289 次取数，而被拉起来的邻居会成片进入这张表；它们的续作要等到自己的 fill
                // 被要时才提升为可扫项，见 pullDependencies。这一条把 tick 成本从随探索增长压回随驱动面
                // 增长。
                continue;
            }
            if (!referencesBuildable(pending.level(), chunk)) {
                continue;
            }
            AWAITING.remove(key);
            try {
                pending.resume().run();
            } finally {
                releasePins(pending);
            }
        }
    }

    /** 停服清空三张表。不动票：level 正在关闭，票存储随它一起消亡。 */
    public static void clearWorldState() {
        STARTED.clear();
        AWAITING.clear();
        PIN_REFS.clear();
        shellChanged = true;
    }

    /**
     * 卸载清理：起点标记随该 chunk 一起丢，重载时起点由读盘填回、标记重算；该中心为依赖补的票也要
     * 撤掉，漏撤会把邻居永久 pin 住。本方法可能被卸载线程调到，票的撤除自己会 marshal 回主线程。
     */
    public static void clearChunk(ResourceKey<Level> dimension, long chunkKey) {
        Set<Long> started = STARTED.get(dimension);
        if (started != null) {
            started.remove(chunkKey);
        }
        Pending pending = AWAITING.remove(new Key(dimension, chunkKey));
        if (pending != null) {
            releasePins(pending);
        }
        shellChanged = true; // 这一格没了，等它的项要重新判
    }

    /** 撤掉某个挂起项补过的全部依赖票。 */
    private static void releasePins(Pending pending) {
        if (pending.pinned() == null) {
            return;
        }
        for (ChunkPos pos : pending.pinned()) {
            unpinRef(pending.level(), pos);
        }
    }

    /** 引用计数加一，0 到 1 时真的铺票。主线程调用。 */
    private static void pinRef(ServerLevel level, ChunkPos pos) {
        Map<Long, Integer> refs = PIN_REFS.computeIfAbsent(level.dimension(),
                k -> new ConcurrentHashMap<>());
        Integer now = refs.compute(pos.pack(), (k, v) -> v == null ? 1 : v + 1);
        if (now != null && now == 1) {
            GenQueue.pinStructureDependency(level, pos);
        }
    }

    /** 引用计数减一，1 到 0 时真的撤票。撤票自己会 marshal 回主线程，所以本方法可从卸载线程调。 */
    private static void unpinRef(ServerLevel level, ChunkPos pos) {
        Map<Long, Integer> refs = PIN_REFS.get(level.dimension());
        if (refs == null) {
            return;
        }
        boolean[] last = { false };
        refs.compute(pos.pack(), (k, v) -> {
            if (v == null) {
                return null;
            }
            if (v <= 1) {
                last[0] = true;
                return null;
            }
            return v - 1;
        });
        if (last[0]) {
            GenQueue.unpinStructureDependency(level, pos);
        }
    }
}
