package com.inf.farlands.terrain.structure;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.decorationFiller.DecorationRegion;
import com.inf.farlands.terrain.decorationFiller.ScopedStructureManager;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 结构两相的本体。方法体调 vanilla 的实现，都在主线程跑。
 *
 * <p>依赖顺序，三条都是铁的：
 *
 * <ol>
 * <li><b>起点相</b>在 {@link #createStructures}，只读中心自己，写也只写中心自己的
 * {@code structureStarts}。它不需要任何邻居，所以没有门；跑不跑由地物族的
 * {@code DecorationSystem#createStructures} 决定，跑过的由 {@code STARTED} 记住。</li>
 * <li><b>引用相</b>在 {@link #buildReferences}，读 ±8 的起点表，即 {@code createReferences} 的
 * 17×17 起点网格，写中心自己的引用表。±8 是 vanilla FEATURES 步对 {@code STRUCTURE_STARTS} 的依赖
 * 半径，见 {@code ChunkPyramid}，也是 vanilla 区域允许的读半径。</li>
 * <li><b>Beardifier</b> 读中心的引用表，再按引用取各 chunk 的起点；它必须早于 biome 与 fill。</li>
 * </ol>
 *
 * <p>谁在什么时候调：起点相由存在流程在壳建好之后无条件调一次；引用相与 Beardifier 由调度器的准入
 * 按需调，判据是 {@link #referencesBuildable}，即中心 ±8 的壳都在场。壳由玩家环票与任务面的 hold 保着，
 * 见 {@link com.inf.farlands.terrain.pipeline.NeighborhoodTickets}，所以本类不再铺票、不再挂起、不再
 * 轮询。
 *
 * <p>为什么结构相在主线程序列：两相写的是 {@code ChunkAccess.structureStarts} 与
 * {@code structuresRefences}，那是 {@code Maps.newHashMap()}，而主线程在读它们，算 Beardifier 与
 * 存盘的 copyOf 都读。
 */
public final class StructureDriver {

    /** 引用相的读半径：vanilla 的 STRUCTURE_STARTS 依赖半径。 */
    public static final int STRUCTURE_READ_RADIUS = 8;

    /** 起点已算的 chunk，按维度分表，键是 ChunkPos.pack。卸载即清，见 {@link #clearChunk}。 */
    private static final Map<ResourceKey<Level>, Set<Long>> STARTED = new ConcurrentHashMap<>();

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
     * {@code getFirstOccupiedHeight}，再到 {@code iterateNoiseColumn}，那里构造 {@code NoiseChunk}，
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

    /**
     * 某一格的壳出现或消失。结构相的就绪只由准入判，本方法保留为空以不动调用点。
     */
    public static void cellChanged() {
    }

    /** 停服清空起算表。不动票：level 正在关闭，票存储随它一起消亡。 */
    public static void clearWorldState() {
        STARTED.clear();
    }

    /** 卸载清理：起点标记随该 chunk 一起丢，重载时起点由读盘填回、标记重算。 */
    public static void clearChunk(ResourceKey<Level> dimension, long chunkKey) {
        Set<Long> started = STARTED.get(dimension);
        if (started != null) {
            started.remove(chunkKey);
        }
    }
}
