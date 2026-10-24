package com.inf.farlands.terrain.decorationFiller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.util.window.WindowedChunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * 装饰期的生成区域，对应 vanilla 的 {@code WorldGenRegion}。
 *
 * <p>
 * 为什么自研：vanilla 那条路的写入走 {@code LevelChunk.setBlockState}，而它的副作用里有
 * {@code state.onPlace} 到 scheduleTick。本 port 的 chunk 从存在流程起就是 LevelChunk，且当场把
 * 自己的 {@code LevelChunkTicks} 注册进了 {@code LevelTicks}，池线程上 add 会与服务端线程的 poll
 * 交错，破坏那个非线程安全的堆。所以写入按 fill、surface、carve 三处的既有取舍走直写 section，
 * 副作用自己接。
 *
 * <p>
 * 取数只走主线程交进来的句柄：chunk 的窗口容器与服务端的 chunk 表都不是线程安全的，池线程不得
 * 再查。越出读域、或读域内没交句柄，才抛 {@link DecorationAbort}，由驱动留表重试。
 *
 * <p>
 * 反过来，vanilla 的两种正常边界在这里不抛：写到写域外，即 {@code ensureCanWrite} 返回假，以及
 * 写到尚未物化的段，都是丢弃这次写并返回假，与 vanilla 写域外丢写同形。地物按高度图落点，而本
 * port 的段按窗口段物化，带外的地形尚不存在，写进去没有落点；这不是异常，是这套生成模型的边界。
 *
 * <p>
 * 池上做的副作用：四张高度图、段空态翻转、光照属性变化时的天光光源列更新与 checkBlock。方块
 * 实体只建出来放在本区域，由主线程的收尾安装：那张表是主线程独占状态。
 */
public final class DecorationRegion implements WorldGenLevel {

    /**
     * 写域半径。与所有权表的 DECORATED 相同，两边都由 vanilla 的 blockStateWriteRadius(1) 定。
     */
    private static final int WRITE_RADIUS = 1;

    /**
     * 读域半径，比写域宽一格。vanilla 在 FEATURES 步的读依赖到半径 8，而写只有 ±1；本 port 没有那份
     * 依赖机制，所以读到 ±2，覆盖随机偏移与矿脉探针那类越出写域一格的读。再外面读不到就放弃本次。
     */
    public static final int READ_RADIUS = 2;

    /** 本区域实例允许的读半径：地物相用 {@link #READ_RADIUS}，结构引用相用 8。 */
    private final int readRadius;

    /**
     * 地物下行扫描的地板，读域里最低的已物化段再往下 8 格；一个段都没有时是
     * {@link Integer#MIN_VALUE}，表示不设下界。
     *
     * <p>池上的地物循环要一个竖直下界：vanilla 那些柱状下行以世界下界为界，而本 port 的地表在十亿格
     * 量级、世界下界仍是 -64，于是没有可放置方块的那些列会把整个跨度走完。地板取读域里的最小值，因为
     * 循环拿不到列坐标，对每一列都成立的地板必须不高于任何一列的最低段。
     *
     * <p>取段容器而不是读 {@code getWindowMinY()}：服务端 chunk 的窗口是构造默认的死状态，不跟生成走。
     * 主线程算好交下来，池上只读这个 final 字段。
     */
    private final int descentFloorY;

    /**
     * 本区域自己的随机源。不能借 {@code level.getRandom()}：那是带并发检测的
     * {@code LegacyRandomSource}，而本区域跑在 farlands-gen 上，主线程同时在用它，刷怪与降水都读它，
     * 共用即 "Accessing LegacyRandomSource from multiple threads" 崩溃。vanilla 的 WorldGenRegion
     * 也是自建，下面构造器里的表达式逐字照抄它。
     */
    private final RandomSource random;

    private final ServerLevel level;
    private final LevelChunk center;
    private final int centerChunkX;
    private final int centerChunkZ;

    /** 主线程交进来的九格句柄，键是 {@link ChunkPos#pack(int, int)}。 */
    private final Long2ObjectMap<LevelChunk> handles;

    /** 本次装饰建出的方块实体，主线程收尾安装；安装前不持有 live level。 */
    private final Map<BlockPos, BlockEntity> pendingBlockEntities = new HashMap<>();

    /**
     * 方块实体装好之后要跑的回调，键是方块坐标。
     *
     * <p>
     * 有些写入要求方块实体已经挂上活 level，例如告示牌的文本：{@code setText} 内部会读 level 发方块更新，
     * 而装饰期 level 被置空。装饰体在池线程上登记，收尾在主线程触发。
     */
    private final Map<BlockPos, Consumer<BlockEntity>> installedHooks = new HashMap<>();

    /**
     * 本次装饰把 BE 方块换成非 BE 方块的那些位置，主线程收尾摘掉那里可能存在的活实例。
     *
     * <p>
     * 直写段绕开了 {@code LevelChunk.setBlockState} 的摘除，而本区域不碰活表，所以只能池上记账、
     * 主线程执行。记与不记只由写自身的返回值与新区块态决定，两者都在池线程本地，不读任何活状态。
     */
    private final Set<BlockPos> pendingBlockEntityRemovals = new HashSet<>();

    /** 本次装饰写到过的 chunk，收尾按它补发下发标记。写域是九格，键是 pack。 */
    private final LongSet writtenChunks = new LongOpenHashSet();

    private final AtomicLong subTickCount = new AtomicLong();

    private Supplier<String> currentlyGenerating;

    DecorationRegion(ServerLevel level, LevelChunk center, Long2ObjectMap<LevelChunk> handles, int readRadius,
            int descentFloorY) {
        this.level = level;
        this.center = center;
        this.centerChunkX = center.getPos().x();
        this.centerChunkZ = center.getPos().z();
        this.handles = handles;
        this.readRadius = readRadius;
        this.descentFloorY = descentFloorY;
        this.random = level.getChunkSource().randomState()
                .getOrCreateRandomFactory(
                        net.minecraft.resources.Identifier.withDefaultNamespace("worldgen_region_random"))
                .at(center.getPos().getWorldPosition());
    }

    /**
     * 只读的 region，供主线程上的结构引用相：句柄按需向 SectionLifecycle.latestChunk 取，不预交
     * 快照。主线程合法，所以不需要池任务那种先取好再交下去的约束。
     *
     * <p>
     * 读半径 8 是 vanilla FEATURES 步对 STRUCTURE_STARTS 的依赖半径，也是 createReferences 的
     * 17×17 起点网格要扫的范围。
     */
    public static DecorationRegion readingFrom(ServerLevel level, LevelChunk center, int readRadius) {
        // 必须重写 get(long)：调用点传的是 long，重载解析命中 Long2ObjectFunction.get(long)，
        // 不会走 Map.get(Object)，写成 AbstractMap 那套会永远返回 null，把每一次读打成 not-handed-in。
        Long2ObjectMap<LevelChunk> lazy = new Long2ObjectOpenHashMap<LevelChunk>() {
            @Override
            public LevelChunk get(long packed) {
                return com.inf.farlands.serialize.SectionLifecycle.latestChunk(level, ChunkPos.getX(packed),
                        ChunkPos.getZ(packed));
            }
        };
        return new DecorationRegion(level, center, lazy, readRadius, lowestSectionFloor(center));
    }

    /**
     * 该 chunk 已物化段里最低的那一段，换算成下行地板；一个段都没有时返回 {@link Integer#MIN_VALUE}，
     * 表示不设下界。
     *
     * <p>段容器是读路径的唯一来源：本 port 的 {@code LevelChunk.getBlockState} 只在容器里有该段且该段非
     * 全空气时才读它，缺段直接是空气。所以地板以下的读永远命中不了 tag，地板只压缩步数。
     */
    static int lowestSectionFloor(LevelChunk chunk) {
        int lowest = Integer.MAX_VALUE;
        for (int sectionY : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            if (sectionY < lowest) {
                lowest = sectionY;
            }
        }
        return lowest == Integer.MAX_VALUE ? Integer.MIN_VALUE : descentFloor(lowest);
    }

    /**
     * 已物化段号换算成下行地板：左移四位得方块 Y，再往下留 8 格。
     *
     * <p>留余量是必要的：下行循环的条件形如 {@code y > 地板 + 3}，地板压到最低段本身会漏掉该段内最底
     * 几格的命中。乘法走 long，可表示段号两端乘 16 正好压在 int 边上。
     */
    static int descentFloor(int lowestSectionY) {
        long floor = (long) lowestSectionY * 16L - 8L;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, floor));
    }

    /** 地物下行扫描的地板。主线程算好交下来，池上只读。没有段时是不设下界的哨兵值。 */
    public int descentFloorY() {
        return this.descentFloorY;
    }

    /** 本次装饰建出的方块实体。主线程收尾安装。 */
    Map<BlockPos, BlockEntity> pendingBlockEntities() {
        return this.pendingBlockEntities;
    }

    /** 登记「该坐标的方块实体装好之后」要做的事。装饰体在池线程上登记，收尾在主线程执行。 */
    public void onBlockEntityInstalled(BlockPos pos, Consumer<BlockEntity> hook) {
        this.installedHooks.put(pos.immutable(), hook);
    }

    /** 收尾装好一个方块实体之后调用。主线程。 */
    void fireBlockEntityInstalled(BlockEntity blockEntity) {
        Consumer<BlockEntity> hook = this.installedHooks.remove(blockEntity.getBlockPos());
        if (hook != null) {
            hook.accept(blockEntity);
        }
    }

    /** 本次装饰写过、且写后不是 BE 方块的位置。主线程收尾摘除。 */
    Set<BlockPos> pendingBlockEntityRemovals() {
        return this.pendingBlockEntityRemovals;
    }

    /**
     * 记一笔「这一写把 BE 方块换掉了」。本类的 {@link #setBlock} 与矿石那条直写旁路都从这里补记。
     *
     * <p>
     * 不需要判那里有没有活的实例：那要读主线程独占的表，池上不许。摘的时候由收尾按当前方块判，
     * 不存在的实例 {@code removeBlockEntity} 自己幂等。
     */
    public void recordBlockEntityReplacement(BlockPos pos) {
        this.pendingBlockEntityRemovals.add(pos.immutable());
    }

    /** 本次装饰写到过的 chunk 键。收尾补发下发标记用。 */
    LongSet writtenChunks() {
        return this.writtenChunks;
    }

    // ---- 取数 ----

    @Override
    public ChunkAccess getChunk(int chunkX, int chunkZ) {
        return this.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, false);
    }

    /**
     * 取数。目标状态不参与判定：本 port 没有 vanilla 的 chunk 状态阶梯，而门已经保证九宫格都过
     * CARVERS，那正是 FEATURES 步对邻居的要求。
     */
    @Override
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus, boolean loadOrGenerate) {
        if (Math.max(Math.abs(chunkX - this.centerChunkX), Math.abs(chunkZ - this.centerChunkZ)) > this.readRadius) {
            throw new DecorationAbort(this.describe(chunkX, chunkZ, "out-of-read-domain"));
        }
        LevelChunk chunk = this.handles.get(ChunkPos.pack(chunkX, chunkZ));
        if (chunk == null) {
            throw new DecorationAbort(this.describe(chunkX, chunkZ, "not-handed-in"));
        }
        return chunk;
    }

    /**
     * 读域内视为有 chunk。语义与 vanilla 同名方法一致：只看这一生成步的依赖半径，不看 chunk 是否
     * 真的加载；本步的写依赖是写域，读依赖是读域，取后者。
     *
     * <p>父接口在 26.1.2 把这个方法标了 {@code @Deprecated}，但它仍是抽象方法，实现类必须给出实现；
     * 屏蔽只覆盖这一处，本类其它弃用调用照旧会报。
     */
    @Override
    @SuppressWarnings("deprecation")
    public boolean hasChunk(int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - this.centerChunkX), Math.abs(chunkZ - this.centerChunkZ)) <= this.readRadius;
    }

    /** 取数落空时的说明串：请求的格、中心、距离，以及是越域还是没交句柄。 */
    private String describe(int chunkX, int chunkZ, String reason) {
        int distance = Math.max(Math.abs(chunkX - this.centerChunkX), Math.abs(chunkZ - this.centerChunkZ));
        return "farlands: decoration aborted requested=" + chunkX + "," + chunkZ
                + " center=" + this.centerChunkX + "," + this.centerChunkZ
                + " distance=" + distance + " reason=" + reason;
    }

    private LevelChunk chunkAt(int blockX, int blockZ) {
        return (LevelChunk) this.getChunk(SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockZ));
    }

    private LevelChunk chunkOf(BlockPos pos) {
        return this.chunkAt(pos.getX(), pos.getZ());
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.chunkOf(pos).getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.chunkOf(pos).getFluidState(pos);
    }

    /**
     * 与 vanilla 同形：委托 chunk 的高度图，末尾加一，与 {@code Heightmap.setHeight} 的减一配套。
     * 四张最终高度图由驱动在跑这一遍之前 prime 过，邻居的由它们自己的 carvers 阶段 prime。
     */
    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return this.chunkAt(x, z).getHeight(type, x & 15, z & 15) + 1;
    }

    /**
     * 先查本次装饰建出的实体，再查 chunk 自己那份。
     *
     * <p>
     * 残留一条：vanilla 的 chunk 查询在命中待建条目时会把那条从待建表里移走，而那张表是 chunk 的
     * plain map。装饰期该 chunk 被写域认领覆盖，主线程的存盘与清理因此跳过，但玩家交互路径不查
     * 认领，属已知残留。
     */
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        BlockEntity pending = this.pendingBlockEntities.get(pos);
        if (pending != null) {
            return pending;
        }
        return this.chunkOf(pos).getBlockEntity(pos);
    }

    // ---- 写入 ----

    /**
     * 是否可写：只判 XZ 是否在写域内。越域记一条错误并返回假，与 vanilla 打日志返回假同形；这条门
     * 只在本 port 自己算错时才被触发，不该让整个服务器为一次越域写入崩掉。
     */
    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        int dx = Math.abs(this.centerChunkX - SectionPos.blockToSectionCoord(pos.getX()));
        int dz = Math.abs(this.centerChunkZ - SectionPos.blockToSectionCoord(pos.getZ()));
        if (dx <= WRITE_RADIUS && dz <= WRITE_RADIUS) {
            return true;
        }
        // 带上此刻在放什么：vanilla 的同名方法也在这里读它，作用是把越域那次写归到具体地物上。
        // 不读它就是只写不读的字段，而这正是排查越域时唯一有用的上下文。
        Supplier<String> generating = this.currentlyGenerating;
        InfsFarlands.LOGGER.error("farlands: decoration wrote outside the write domain pos={} center={}{}",
                pos, this.center.getPos(),
                generating == null ? "" : ", currently generating: " + generating.get());
        return false;
    }

    /**
     * 直写方块。与 {@code LevelChunk.setBlockState} 的差别逐条：
     *
     * <ul>
     * <li>段写走五参无检测重载，与 fill、surface、carve 三处同形，并在
     * {@link SectionSerializer#packLockFor(long)} 内做：编码池与主线程的三个打包点用同一把锁，
     * 装饰的池上写入因此与它们互斥，不靠调色板的检测器。</li>
     * <li>不调 {@code state.onPlace}，因此不排计划刻：池线程排计划刻会碰 {@code LevelChunkTicks}
     * 的裸堆。</li>
     * <li>不做邻居更新：装饰只放方块。</li>
     * <li>方块实体只建出来放进本区域，由主线程收尾安装，因为 {@code blockEntities} 与 ticker 表是
     * 主线程独占状态。</li>
     * <li>把 BE 方块换成非 BE 方块时，位置记进本区域，由主线程收尾摘掉那里的活实例。vanilla 的
     * {@code WorldGenRegion.setBlock} 是当场摘的，本区域碰不了活表，只能分池上记账与主线程执行两段。</li>
     * </ul>
     */
    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int updateFlags, int updateLimit) {
        if (!this.ensureCanWrite(pos)) {
            return false;
        }
        LevelChunk owner = this.chunkOf(pos);
        int y = pos.getY();
        LevelChunkSection section = ((WindowedChunk) owner).windowedAllSections().get(y >> 4);
        if (section == null) {
            // 该 Y 的段没物化：本 port 按窗口段生成，带外的地形尚不存在，这次写没有落点。与 vanilla
            // 写域外丢写同形，返回假、不改任何状态，不把模型边界当异常。
            return false;
        }
        int localX = pos.getX() & 15;
        int localY = y & 15;
        int localZ = pos.getZ() & 15;
        boolean wasEmpty = section.hasOnlyAir();
        BlockState oldState;
        // 段写与四张高度图必须在同一把锁内：玩家写那边由 serialize/LevelChunkMixin 同样把高度图更新
        // 纳入这把锁，两侧因此互斥，否则并发写会撞同一个 BitStorage。
        synchronized (SectionSerializer.packLockFor(owner.getPos().pack())) {
            oldState = section.setBlockState(localX, localY, localZ, state, false);
            this.updateHeightmaps(owner, localX, y, localZ, state);
        }
        boolean isEmpty = section.hasOnlyAir();
        if (wasEmpty != isEmpty) {
            // 服务端下 updateSectionStatus 只入队，池上调用与 fill 同类。
            this.level.getChunkSource().getLightEngine().updateSectionStatus(SectionPos.of(pos), isEmpty);
        }
        if (LightEngine.hasDifferentLightProperties(oldState, state)) {
            owner.getSkyLightSources().update(owner, localX, y, localZ);
            this.level.getChunkSource().getLightEngine().checkBlock(pos);
        }
        if (state.hasBlockEntity()) {
            BlockEntity blockEntity = ((EntityBlock) state.getBlock()).newBlockEntity(pos.immutable(), state);
            if (blockEntity != null) {
                this.pendingBlockEntities.put(pos.immutable(), blockEntity);
            }
        } else {
            if (!this.pendingBlockEntities.isEmpty()) {
                this.pendingBlockEntities.remove(pos);
            }
            if (oldState != null && oldState.hasBlockEntity()) {
                // 这一写把 BE 方块换掉了。vanilla 在 WorldGenRegion.setBlock 里当场摘活实例，本区域
                // 不碰活表，只能记一笔交给主线程收尾。
                recordBlockEntityReplacement(pos);
            }
        }
        this.writtenChunks.add(owner.getPos().pack());
        return true;
    }

    /**
     * 四张最终高度图增量更新，与 vanilla setBlockState 的同名四行同源。
     *
     * <p>必须在 {@link SectionSerializer#packLockFor(long)} 内调用：玩家写那边由
     * {@code serialize/LevelChunkMixin} 把同样四次更新纳入了同一把锁。这四张图在 LevelChunk 构造时
     * 就已建好，所以这里的取用只是查表，不会改那张 map。
     */
    private void updateHeightmaps(LevelChunk owner, int localX, int y, int localZ, BlockState state) {
        owner.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING).update(localX, y, localZ, state);
        owner.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).update(localX, y, localZ, state);
        owner.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR).update(localX, y, localZ, state);
        owner.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).update(localX, y, localZ, state);
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
        return this.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropResources, Entity breaker, int updateLimit) {
        return this.getBlockState(pos).isAir() ? false
                : this.setBlock(pos, Blocks.AIR.defaultBlockState(), 3, updateLimit);
    }

    /** 装饰只放方块，不加实体。 */
    @Override
    public boolean addFreshEntity(Entity entity) {
        return false;
    }

    // ---- 不属于本步的两块：位置门与方块实体辅助 ----

    /**
     * 最近一次经 {@link UnlockedSectionAccess} 交出去的段：所属区域、chunk 键与那个世界坐标。
     *
     * <p>
     * 矿石那一路绕过 {@link #setBlock} 直接写段，而 {@code LevelChunkSection} 不持有 chunk 引用：
     * 写点取包锁要 chunk 键，把 BE 方块换掉时要位置，两样都由取段那一侧留在线程上。
     *
     * <p>
     * 用可变 holder 而不是每次新建：一次矿石生成会按被测位置反复调 {@code getSection}，按位置分配会在
     * 池线程上堆垃圾。装饰整段跑在同一条池线程上，任务收尾清掉。
     */
    private static final class HandedSection {
        private DecorationRegion region;
        private long chunkKey;
        private int x;
        private int y;
        private int z;
    }

    private static final ThreadLocal<HandedSection> HANDED = ThreadLocal.withInitial(HandedSection::new);

    /** 最近交出去的段所属 chunk 键，没有则 null。供 OreFeatureSectionAccessMixin 取锁用。 */
    public static Long handedChunkKey() {
        HandedSection handed = HANDED.get();
        return handed.region == null ? null : handed.chunkKey;
    }

    /**
     * 把最近一次交出去的世界坐标记为「BE 方块被换掉」，供矿石写点调用。没有记录时什么都不做。
     *
     * <p>
     * 位置取最近一次 {@code getSection} 的入参，与那次写在同轮循环内相邻，所以是准的。
     */
    public static void recordHandedBlockEntityReplacement() {
        HandedSection handed = HANDED.get();
        if (handed.region != null) {
            handed.region.recordBlockEntityReplacement(new BlockPos(handed.x, handed.y, handed.z));
        }
    }

    /** 清掉本线程的记录。装饰任务收尾调用，防同一条池线程把上一个任务的值带给下一个。 */
    public static void clearHandedChunkKey() {
        HANDED.remove();
    }

    /**
     * 无锁取段的 {@link BulkSectionAccess} 替身，供 {@code OreFeature} 用。
     *
     * <p>
     * vanilla 那个在 getSection 里对段调 acquire，那是调色板的并发检测器：抢失败靠抛异常把许可
     * 交出去，而异常被编码侧的 catch 吞掉之后许可就永久占用。这里只需要读，而
     * {@code LevelChunkSection.getBlockState} 走调色板的 get，本来就不加锁。
     *
     * <p>它交出去的段随后由矿石直接写，那一次写由 {@code OreFeatureSectionAccessMixin} 用同一把包锁
     * 包住；锁键与那一写的位置都从本类的 {@link #handedChunkKey()} 与
     * {@link #recordHandedBlockEntityReplacement()} 取。
     */
    public static final class UnlockedSectionAccess extends BulkSectionAccess {

        private final DecorationRegion region;

        public UnlockedSectionAccess(DecorationRegion region) {
            super(region);
            this.region = region;
        }

        @Override
        public LevelChunkSection getSection(BlockPos pos) {
            LevelChunk owner = this.region.chunkOf(pos);
            LevelChunkSection section = ((WindowedChunk) owner).windowedAllSections().get(pos.getY() >> 4);
            // 可空是 vanilla BulkSectionAccess 的契约，OreFeature 判空之后才写；段没物化时返回 null，
            // 与这次写没有落点同义。
            if (section != null) {
                // 它绕过本区域的 setBlock 直接写段，所以写过的 chunk 要在这里补记，收尾才补得上下发；
                // 写那一次要取的包锁键、以及 BE 方块被换掉时要记的位置，也都从这里交出去。
                this.region.writtenChunks.add(owner.getPos().pack());
                HandedSection handed = HANDED.get();
                handed.region = this.region;
                handed.chunkKey = owner.getPos().pack();
                handed.x = pos.getX();
                handed.y = pos.getY();
                handed.z = pos.getZ();
            }
            return section;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.region.getBlockState(pos);
        }

        @Override
        public void close() {
            // 没有取过锁，也没有需要归还的段。
        }
    }

    // ---- 委托 level 的其余面 ----

    @Override
    public LevelLightEngine getLightEngine() {
        return this.level.getLightEngine();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return this.level.getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        return this.level.getUncachedNoiseBiome(quartX, quartY, quartZ);
    }

    /** 难度按 level 现算，不走 hasChunk 那条判据：装饰期的 chunk 表在池线程上不可查。 */
    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return new DifficultyInstance(this.level.getDifficulty(), this.level.getOverworldClockTime(), 0L,
                this.level.getMoonBrightness(pos));
    }

    @Override
    public ServerLevel getLevel() {
        return this.level;
    }

    @Override
    public RegistryAccess registryAccess() {
        return this.level.registryAccess();
    }

    @Override
    public LevelData getLevelData() {
        return this.level.getLevelData();
    }

    @Override
    public DimensionType dimensionType() {
        return this.level.dimensionType();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.level.enabledFeatures();
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        return EnvironmentAttributeReader.EMPTY;
    }

    @Override
    public MinecraftServer getServer() {
        return this.level.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        return this.level.getChunkSource();
    }

    @Override
    public RandomSource getRandom() {
        return this.random;
    }

    @Override
    public long getSeed() {
        return this.level.getSeed();
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return this.level.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return this.level.getMinY();
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return this.level.getWorldBorder();
    }

    @Override
    public long nextSubTickCount() {
        return this.subTickCount.getAndIncrement();
    }

    /**
     * 装饰期不排计划刻：池线程往 {@code LevelChunkTicks} 的裸堆里 add，会与服务端线程的 poll 交错。
     * 与 fill、surface、carve 三处的取舍同规。
     */
    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return NoopTicks.BLOCK;
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return NoopTicks.FLUID;
    }

    @Override
    public List<Player> players() {
        return List.of();
    }

    @Override
    public List<Entity> getEntities(Entity except, AABB bb, Predicate<? super Entity> selector) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bb,
            Predicate<? super T> selector) {
        return List.of();
    }

    @Override
    public Player getNearestPlayer(double x, double y, double z, double maxDist, Predicate<Entity> predicate) {
        return null;
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(this.getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(this.getFluidState(pos));
    }

    @Override
    public void playSound(Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
            float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double xd, double yd,
            double zd) {
    }

    @Override
    public void levelEvent(Entity source, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
    }

    @Override
    public void setCurrentlyGenerating(Supplier<String> currentlyGenerating) {
        this.currentlyGenerating = currentlyGenerating;
    }

    @Override
    public String toString() {
        return "DecorationRegion[" + this.center.getPos() + "]";
    }

    /** 空实现的 tick 访问器，见 {@link #getBlockTicks()}。 */
    private static final class NoopTicks<T> implements LevelTickAccess<T> {

        static final NoopTicks<Block> BLOCK = new NoopTicks<>();
        static final NoopTicks<Fluid> FLUID = new NoopTicks<>();

        @Override
        public void schedule(ScheduledTick<T> tick) {
        }

        @Override
        public boolean hasScheduledTick(BlockPos pos, T type) {
            return false;
        }

        @Override
        public boolean willTickThisTick(BlockPos pos, T type) {
            return false;
        }

        @Override
        public int count() {
            return 0;
        }
    }
}
