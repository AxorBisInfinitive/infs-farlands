package com.inf.farlands.client.simulation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.util.world.WorldBounds;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * 一段 16 立方的模拟方块，坐标为段坐标，三轴都是 long。
 *
 * <p>
 * 三轴段坐标都落在可玩段界之内的段是失效段：{@link #set} 直接返回，不写槽也不记脏。这样的段不进快照，
 * 于是既不渲染也不参与碰撞，且不打日志。判据取可玩段界而不是可表示段界，因为可表示界两端之外还有一整段
 * 可玩的方块在界内，那些段要继续工作。
 *
 * <p>
 * 群系是段内 4x4x4 的 quart 网格，与 {@code LevelChunkSection} 同构：三轴是 quart 坐标 0 到 3，写路径是
 * {@code recreate} 后整体换引用，读者不会看到写了一半的容器。默认值建段时传入，模拟之地用 void。
 *
 * <p>
 * 光照是两片 {@code DataLayer}，四 bit 一格、2048 字节一层，与引擎侧那份同构。默认 0 时是同质的未分配层，
 * 不占数组；它只存不算是既定契约，传播与发包都不经过这里。
 *
 * <p>
 * 内容以 {@link Content} 发布：一次应用建一份新的、发布之后不再改。烘焙线程只读它，主线程换它，因此工作线程
 * 拿到的永远是某一代的完整内容，不会看到写了一半的数组。本类自己的字段只是同一份内容的活视图，供碰撞与拾取在
 * 主线程上按需读取。
 */
public final class SimulationSection {

    /** 段边长。 */
    public static final int SIZE = 16;

    /** 每轴 quart 格数，即群系网格的边长。 */
    public static final int QUART = SIZE / 4;

    /**
     * 一份已发布的内容。发布后各字段不再改：方块槽位数组与占用表都是新建的，两层光照与群系容器都是换引用。
     *
     * <p>
     * 烘焙线程持它算几何，因此它必须自足：所有需要的数据都在这里，不必回头读 {@link SimulationSection}。
     * {@code occupied} 与 {@code occupiedCount} 一起发布，数组尾部可能有未用槽位。
     */
    record Content(SimulationBlock[] slots, int[] occupied, int occupiedCount, DataLayer blockLight,
            DataLayer skyLight, PalettedContainerRO<Holder<Biome>> biomes, int shapeMode) {

        /** 第 i 个非空格的状态，空位返回 null。 */
        SimulationBlock blockAt(int index) {
            SimulationBlock[] array = this.slots;
            return array == null ? null : array[index];
        }

        /** 单通道的光，0 到 15。 */
        int light(LightLayer layer, int localX, int localY, int localZ) {
            return (layer == LightLayer.SKY ? this.skyLight : this.blockLight).get(localX, localY, localZ);
        }

        boolean isEmpty() {
            return this.occupiedCount == 0;
        }
    }

    private final long sectionX;
    private final long sectionY;
    private final long sectionZ;

    /** 可玩界外为真，界内为假。界内的段静默失效。 */
    private final boolean eligible;

    /**
     * 段内 4x4x4 群系网格，三轴是 quart 坐标 0 到 3。写路径换引用，不原地改。
     */
    private volatile PalettedContainerRO<Holder<Biome>> biomes;

    /** 天光与方块光，四 bit 一格。默认 0 时是同质未分配层，不占数组。 */
    private DataLayer skyLight = new DataLayer();
    private DataLayer blockLight = new DataLayer();

    /**
     * 4096 个格的方块，未写过时为 null。
     *
     * <p>
     * 不预分配：视距内每一段都会建对象，其中绝大多数是空段，而一个 4096 长的引用数组本身就是 16KB，
     * 按段乘起来是几百 MB。第一次写非空方块时才分配，空段因此只付对象头与几个字段。
     */
    private SimulationBlock[] slots;

    private int count;

    /** 段级形状模式，来自服务端，取值见 {@link SimulationSectionData}。 */
    private int shapeMode = SimulationSectionData.SHAPE_DERIVED;

    private boolean dirty;

    /** 脏范围的段内局部坐标，供重烘与邻列失效用。干净时 min 大于 max。 */
    private int dirtyMinX = Integer.MAX_VALUE;
    private int dirtyMaxX = Integer.MIN_VALUE;
    private int dirtyMinY = Integer.MAX_VALUE;
    private int dirtyMaxY = Integer.MIN_VALUE;
    private int dirtyMinZ = Integer.MAX_VALUE;
    private int dirtyMaxZ = Integer.MIN_VALUE;

    /** 最近一次重建出来的快照段，空段为 null。 */
    private Simulation.SnapSection snap;

    /**
     * 非空格的槽位下标，供碰撞、拾取与烘几何按需遍历，避免每次扫 4096 槽。
     *
     * <p>
     * 维护点只有 {@link #set}、{@link #clear} 与 {@link #clearAll} 三处，摘除用尾元素顶上，不保序。
     */
    private int[] occupied = new int[16];

    private int occupiedCount;

    /**
     * 内容版本，只在客户端主线程自增。烘焙在工作线程上进行，完成时拿快照里的版本与本字段比，判结果是否已过期。
     */
    private int revision;

    /** 27 个邻位的全集。位序见 {@link #missing}。 */
    static final int ALL_NEIGHBORS_MISSING = (1 << 27) - 1;

    /**
     * 27 位邻域缺失掩码：烘制当前这一版几何时，哪几个邻位还不存在。
     *
     * <p>
     * 位序与 {@code Simulation.farlands$snapshot} 的邻域数组同一套，即
     * {@code (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)}，自分位 13 永不参与判定。缺省全 1：没烘过的段按「邻域全缺」算，
     * 邻段到达时它一定会被放回待烘，不会留下一版在旧邻域下算出的几何。
     */
    private int missing = ALL_NEIGHBORS_MISSING;

    /** 已发布的内容。构造、应用、单格写与清理都会换一份新的。 */
    private volatile Content content;

    /**
     * 每层的几何，索引即 {@code ChunkSectionLayer} 的枚举序，没有几何的层为 null。
     *
     * <p>
     * 挂在段对象上而不是挂在一张按段号索引的表里，是为了让渲染侧取几何只是一次字段读。
     */
    private final Geometry[] geometries = new Geometry[3];

    /** 待发布几何的顶点数，按层。烘制完成时写下，分配落地时与偏移一起发布，见 {@link #publishGeometry}。 */
    private final int[] stagedVertexCounts = new int[3];

    /**
     * 36 位面可见性掩码，6 面两两可达，供 {@link SimulationVisibility} 的遮挡传播用。缺省 {@link
     * SimulationVisibility#OPEN}，即全开。
     *
     * <p>
     * 缺省取全开而不是全闭：掩码尚未算出时按开处理只会少剔几段，按闭处理会把真看得见的几何一起删掉。
     */
    private long visibility = SimulationVisibility.OPEN;

    /**
     * 每格的 tint 缓存，按 resolver 与「Y 的 quart 带」两级。0 表示未算，tint 恒不透明，所以 0 不会是真值。
     *
     * <p>
     * 按带折一是精确的，不是近似：块色的混合取样把 Y 固定在方块自己的 Y 上，而群系按四立方 quart 取值，所以带内
     * 各 Y 的结果相同。缓存跨烘制保留，所以群系一变就必须清，见 {@link #clearTints()}。
     *
     * <p>
     * 并发：烘制在工作线程写它，主线程在段内容变化时清它，所以用并发容器。清发生在烘焙期间时，那一次烘焙的结果会
     * 因版本不符被丢弃，重烘前再清一次，见 {@code Simulation.farlands$drainCompleted}。
     */
    private final Map<ColorResolver, int[][]> tints = new ConcurrentHashMap<>();

    SimulationSection(long sectionX, long sectionY, long sectionZ, PalettedContainerFactory biomeFactory,
            Holder<Biome> defaultBiome) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.eligible = !(farlands$playable(sectionX) && farlands$playable(sectionY)
                && farlands$playable(sectionZ));
        this.biomes = new PalettedContainer<>(defaultBiome, biomeFactory.biomeStrategy());
        this.content = new Content(null, new int[0], 0, this.blockLight, this.skyLight, this.biomes,
                this.shapeMode);
    }

    public long sectionX() {
        return this.sectionX;
    }

    public long sectionY() {
        return this.sectionY;
    }

    public long sectionZ() {
        return this.sectionZ;
    }

    /** 世界坐标的段原点，段坐标乘 16，long 上精确。 */
    public long originX() {
        return this.sectionX * SIZE;
    }

    public long originY() {
        return this.sectionY * SIZE;
    }

    public long originZ() {
        return this.sectionZ * SIZE;
    }

    /** 已发布的内容，工作线程读它。 */
    Content content() {
        return this.content;
    }

    /** 该 quart 格的群系，三轴是 0 到 3。与 {@code LevelChunkSection.getNoiseBiome} 同口径。 */
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
        return this.biomes.get(quartX, quartY, quartZ);
    }

    /**
     * 写一格群系。拷一份容器、改一格再整体换引用，读者不会看到中间态。
     *
     * <p>
     * 拷用 {@code copy()} 而不是 {@code recreate()}：后者按 palette 的第 0 项新建同质容器，会把其余 63 格
     * 重置回那一项，单格写因此退化成整段重置。
     */
    public void setBiome(int quartX, int quartY, int quartZ, Holder<Biome> biome) {
        PalettedContainer<Holder<Biome>> next = this.biomes.copy();
        next.getAndSetUnchecked(quartX, quartY, quartZ, biome);
        this.biomes = next;
        this.clearTints();
        this.revision++;
        this.farlands$publish();
        this.farlands$markDirty(quartX * 4, quartY * 4, quartZ * 4);
        this.farlands$markDirty(quartX * 4 + 3, quartY * 4 + 3, quartZ * 4 + 3);
    }

    /** 填满整段的 quart 网格，一次换引用。 */
    public void fillBiomes(Holder<Biome> biome) {
        PalettedContainer<Holder<Biome>> next = this.biomes.recreate();
        for (int x = 0; x < QUART; x++) {
            for (int y = 0; y < QUART; y++) {
                for (int z = 0; z < QUART; z++) {
                    next.getAndSetUnchecked(x, y, z, biome);
                }
            }
        }
        this.biomes = next;
        this.clearTints();
        this.revision++;
        this.farlands$publish();
        this.farlands$markDirty(0, 0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1,
                SimulationSection.SIZE - 1);
    }

    /** 该格两通道打包后的光，默认 0 即全黑。位段与 {@code LightCoordsUtil.pack} 同。 */
    public int getLight(int localX, int localY, int localZ) {
        return this.blockLight.get(localX, localY, localZ) << 4 | this.skyLight.get(localX, localY, localZ) << 20;
    }

    /** 单通道的光，0 到 15，供 {@code getBrightness} 使用。 */
    public int getLight(LightLayer layer, int localX, int localY, int localZ) {
        return (layer == LightLayer.SKY ? this.skyLight : this.blockLight).get(localX, localY, localZ);
    }

    /** 写一格的两通道光。值域由 {@code DataLayer.set} 掩到 0 到 15。 */
    public void setLight(int localX, int localY, int localZ, int block, int sky) {
        // 换一份层再改：已发布的 Content 持有旧的层对象，工作线程可能在读它。
        this.blockLight = this.blockLight.copy();
        this.skyLight = this.skyLight.copy();
        this.blockLight.set(localX, localY, localZ, block);
        this.skyLight.set(localX, localY, localZ, sky);
        this.farlands$publish();
        this.farlands$markDirty(localX, localY, localZ);
    }

    /** 整层同值。 */
    public void fillLight(int block, int sky) {
        this.blockLight = new DataLayer();
        this.skyLight = new DataLayer();
        this.blockLight.fill(block);
        this.skyLight.fill(sky);
        this.farlands$publish();
        this.farlands$markDirty(0, 0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1,
                SimulationSection.SIZE - 1);
    }

    /** 该段是否参与模拟。可玩界内的段为假。 */
    public boolean eligible() {
        return this.eligible;
    }

    /**
     * 与当前内容比较，不同才应用。返回是否真的换了内容。
     *
     * <p>
     * 比较一趟同时办两件事：判「要不要换」，以及算出方块差异的段内包围盒，供 {@code Simulation.commit} 只让贴着
     * 真差异的邻段失效。光与群系没有位置可言——它们在段边界外被邻段采样——所以只要光或群系变了，整段打脏。
     *
     * <p>
     * 比较的代价是 4096 次容器读取加 64 次群系比较，约几十微秒；它换来的是跳过一整遍内容转换，以及避免下游
     * 27 段重烘。
     */
    public boolean applyIfChanged(LevelChunkSection container, int shapeMode, DataLayer blockLight,
            DataLayer skyLight) {
        if (!this.eligible) {
            return false;
        }
        DataLayer newBlockLight = blockLight == null ? new DataLayer() : blockLight;
        DataLayer newSkyLight = skyLight == null ? new DataLayer() : skyLight;
        SimulationBlock[] current = this.slots;
        int diffMinX = Integer.MAX_VALUE;
        int diffMaxX = Integer.MIN_VALUE;
        int diffMinY = Integer.MAX_VALUE;
        int diffMaxY = Integer.MIN_VALUE;
        int diffMinZ = Integer.MAX_VALUE;
        int diffMaxZ = Integer.MIN_VALUE;
        boolean blocksDiffer = false;
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    BlockState incoming = container.getBlockState(x, y, z);
                    SimulationBlock have = current == null ? null : current[farlands$index(x, y, z)];
                    boolean same = have == null ? incoming.isAir() : have.state() == incoming;
                    if (same) {
                        continue;
                    }
                    blocksDiffer = true;
                    if (x < diffMinX) {
                        diffMinX = x;
                    }
                    if (x > diffMaxX) {
                        diffMaxX = x;
                    }
                    if (y < diffMinY) {
                        diffMinY = y;
                    }
                    if (y > diffMaxY) {
                        diffMaxY = y;
                    }
                    if (z < diffMinZ) {
                        diffMinZ = z;
                    }
                    if (z > diffMaxZ) {
                        diffMaxZ = z;
                    }
                }
            }
        }
        boolean lightSame = farlands$sameLight(this.blockLight, newBlockLight)
                && farlands$sameLight(this.skyLight, newSkyLight);
        boolean shapeSame = this.shapeMode == shapeMode;
        boolean biomeSame = farlands$sameBiomes(this.biomes, container.getBiomes());
        if (!blocksDiffer && lightSame && shapeSame && biomeSame) {
            return false;
        }
        this.applyContainer(container, shapeMode, newBlockLight, newSkyLight);
        if (blocksDiffer) {
            this.farlands$markDirty(diffMinX, diffMinY, diffMinZ);
            this.farlands$markDirty(diffMaxX, diffMaxY, diffMaxZ);
        }
        if (!lightSame || !biomeSame || !shapeSame) {
            this.farlands$markDirty(0, 0, 0);
            this.farlands$markDirty(SIZE - 1, SIZE - 1, SIZE - 1);
        }
        return true;
    }

    /**
     * 用给定的一份内容整体替换本段：块、群系、两层光照与形状模式。
     *
     * <p>
     * 失效段直接返回：可玩界内的段不参与模拟，服务端也不该发它们。
     *
     * <p>
     * 模拟方块按 {@code BlockState} 建小表复用：同一状态共用一个实例，既省分配，也因为 {@link #set} 的
     * {@code previous == block} 是引用比较。
     *
     * <p>
     * 不打脏标记：调用方 {@link #applyIfChanged} 按差异盒自己打。直接调用本方法等于声明「整段都算变了」。
     *
     * <p>
     * 只在客户端主线程调，与包的分发点同线程。
     */
    private void applyContainer(LevelChunkSection container, int shapeMode, DataLayer blockLight,
            DataLayer skyLight) {
        this.revision++;
        SimulationBlock[] nextSlots = null;
        int[] nextOccupied = new int[256];
        int nextOccupiedCount = 0;
        this.count = 0;
        this.biomes = container.getBiomes();
        this.clearTints();
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.shapeMode = shapeMode;
        Map<BlockState, SimulationBlock> cache = new HashMap<>(8);
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    BlockState state = container.getBlockState(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    SimulationBlock block = cache.computeIfAbsent(state,
                            s -> shapeMode == SimulationSectionData.SHAPE_FULL ? SimulationBlock.full(s)
                                    : SimulationBlock.of(s));
                    int index = farlands$index(x, y, z);
                    if (nextSlots == null) {
                        nextSlots = new SimulationBlock[SIZE * SIZE * SIZE];
                    }
                    nextSlots[index] = block;
                    if (nextOccupiedCount == nextOccupied.length) {
                        nextOccupied = Arrays.copyOf(nextOccupied, nextOccupied.length * 2);
                    }
                    nextOccupied[nextOccupiedCount++] = index;
                }
            }
        }
        this.count = nextOccupiedCount;
        this.slots = nextSlots;
        this.occupied = nextOccupied;
        this.occupiedCount = nextOccupiedCount;
        // 新内容还没烘出来，上一版掩码属于上一版几何，按它传播会在等烘制的那几 tick 里剔掉本该看得见的段。
        this.visibility = SimulationVisibility.OPEN;
        this.content = new Content(nextSlots, nextOccupied, nextOccupiedCount, this.blockLight, this.skyLight,
                this.biomes, this.shapeMode);
    }

    /**
     * 应用一段全空的段：只装群系与两层光照，不扫方块。
     *
     * <p>
     * 空段本身没有几何，但它的光照是邻段环境光遮蔽的输入：模拟层的天光 15 与方块光 0 都存在这个段的
     * {@code DataLayer} 里。整段不留对象，邻段朝空气的角点就会采到 0 光，表现为成片纯黑。所以这里保留对象，
     * 只跳过 4096 格方块转换与脏标记。
     *
     * <p>
     * {@code snap} 置空：这一段若是从有方块降成全空，旧快照段必须退出渲染列表，几何由调用方释放。
     */
    void applyAir(LevelChunkSection container, DataLayer blockLight, DataLayer skyLight) {
        if (!this.eligible) {
            return;
        }
        this.revision++;
        this.slots = null;
        this.count = 0;
        this.occupied = new int[0];
        this.occupiedCount = 0;
        this.snap = null;
        this.biomes = container.getBiomes();
        this.clearTints();
        this.blockLight = blockLight == null ? new DataLayer() : blockLight;
        this.skyLight = skyLight == null ? new DataLayer() : skyLight;
        // 全空气必然看得穿，留着上一版全闭的掩码会把这一段后面的所有段一起剔掉。
        this.visibility = SimulationVisibility.OPEN;
        this.content = new Content(null, this.occupied, 0, this.blockLight, this.skyLight, this.biomes,
                this.shapeMode);
    }

    public int count() {
        return this.count;
    }

    /** 写一格。局部坐标必须在 0 到 15 内，失效段上静默丢弃。 */
    public void set(int localX, int localY, int localZ, SimulationBlock block) {
        if (!this.eligible) {
            return;
        }
        int index = farlands$index(localX, localY, localZ);
        SimulationBlock[] current = this.slots;
        SimulationBlock previous = current == null ? null : current[index];
        if (previous == block) {
            return;
        }
        // 换新数组而不是原地改：已发布的 Content 持有旧数组，工作线程可能在读它。
        SimulationBlock[] nextSlots = current == null ? new SimulationBlock[SIZE * SIZE * SIZE] : current.clone();
        int[] nextOccupied;
        int nextCount;
        if (block == null) {
            if (current == null) {
                return;
            }
            nextSlots[index] = null;
            nextCount = this.count - 1;
            nextOccupied = farlands$without(index, this.occupied, this.occupiedCount);
        } else {
            if (previous == null) {
                nextCount = this.count + 1;
                nextOccupied = farlands$with(index, this.occupied, this.occupiedCount);
            } else {
                nextCount = this.count;
                nextOccupied = Arrays.copyOf(this.occupied, this.occupiedCount);
            }
            nextSlots[index] = block;
        }
        this.slots = nextSlots;
        this.count = nextCount;
        this.occupied = nextOccupied;
        this.occupiedCount = nextOccupied.length;
        this.revision++;
        this.farlands$publish();
        this.farlands$markDirty(localX, localY, localZ);
    }

    /** 清一格。 */
    public void clear(int localX, int localY, int localZ) {
        this.set(localX, localY, localZ, null);
    }

    /** 读一格，供碰撞、拾取与命中用。局部坐标必须在 0 到 15 内。 */
    public SimulationBlock blockAt(int localX, int localY, int localZ) {
        SimulationBlock[] current = this.slots;
        return current == null ? null : current[farlands$index(localX, localY, localZ)];
    }

    /** 非空格的数量。 */
    public int occupiedCount() {
        return this.occupiedCount;
    }

    /** 第 i 个非空格的下标，形如 {@code (x << 8) | (y << 4) | z}。 */
    public int occupiedIndex(int i) {
        return this.occupied[i];
    }

    /** 当前内容版本。只在客户端主线程读写。 */
    public int revision() {
        return this.revision;
    }

    /** 当前这一版几何的邻域缺失掩码。见 {@link #missing}。 */
    int missing() {
        return this.missing;
    }

    /** 写入烘制这一版几何时缺席的邻位。 */
    void setMissing(int mask) {
        this.missing = mask;
    }

    /** 36 位面可见性掩码。见 {@link #visibility}。 */
    long visibility() {
        return this.visibility;
    }

    /** 写入烘制期算出的掩码。 */
    void setVisibility(long mask) {
        this.visibility = mask;
    }

    /**
     * 一层的几何：共享顶点缓冲、起始顶点序号、顶点数，以及可选的索引缓冲。
     *
     * <p>
     * 顶点缓冲是 uber 缓冲里的一块 heap，很多段共用它；本层的内容从 {@code baseVertex} 个顶点处开始。
     * {@code indexBuffer} 为 null 时走共享顺序索引，绘制时由 {@code baseVertex} 把零基索引平移到本段；非 null 时是
     * 透明层排过序的自有索引。
     */
    public record Geometry(GpuBuffer buffer, int baseVertex, int vertexCount, GpuBuffer indexBuffer,
            VertexFormat.IndexType indexType, int indexCount) {
    }

    /** 该层的几何，没有则为 null。 */
    public Geometry geometry(ChunkSectionLayer layer) {
        return this.geometries[layer.ordinal()];
    }

    /** 记下待发布几何的顶点数，见 {@link #stagedVertexCounts}。 */
    public void setStagedVertexCount(ChunkSectionLayer layer, int vertexCount) {
        this.stagedVertexCounts[layer.ordinal()] = vertexCount;
    }

    /**
     * 分配落地之后发布该层的几何。缓冲与偏移来自 uber 缓冲的上传回调。
     *
     * <p>
     * 走共享顺序索引的层没有自有索引，索引数由顶点数换算：每四个顶点接六个索引。
     */
    public void publishGeometry(ChunkSectionLayer layer, GpuBuffer buffer, int baseVertex, GpuBuffer indexBuffer,
            VertexFormat.IndexType indexType, int indexCount) {
        int slot = layer.ordinal();
        int vertexCount = this.stagedVertexCounts[slot];
        int count = indexBuffer == null ? vertexCount / 4 * 6 : indexCount;
        this.geometries[slot] = new Geometry(buffer, baseVertex, vertexCount, indexBuffer, indexType, count);
    }

    /** 摘掉这一层的几何。段被摘除或该层不再有几何时调。 */
    public void clearGeometry(ChunkSectionLayer layer) {
        this.geometries[layer.ordinal()] = null;
        this.stagedVertexCounts[layer.ordinal()] = 0;
    }

    /** 摘掉全部层的几何。 */
    public void clearAllGeometry() {
        for (int i = 0; i < this.geometries.length; i++) {
            this.geometries[i] = null;
            this.stagedVertexCounts[i] = 0;
        }
    }

    /** 该格的 tint，未算返回 0。 */
    int cachedTint(ColorResolver resolver, int localX, int localY, int localZ) {
        int[][] bands = this.tints.get(resolver);
        if (bands == null) {
            return 0;
        }
        int[] band = bands[localY >> 2];
        return band == null ? 0 : band[(localZ << 4) | localX];
    }

    /** 记一格的 tint。按需建带，每条带 256 个 int。 */
    void putTint(ColorResolver resolver, int localX, int localY, int localZ, int color) {
        int[][] bands = this.tints.computeIfAbsent(resolver, key -> new int[4][]);
        int bandIndex = localY >> 2;
        int[] band = bands[bandIndex];
        if (band == null) {
            band = new int[256];
            bands[bandIndex] = band;
        }
        band[(localZ << 4) | localX] = color;
    }

    /** 群系或段内容变化后清空 tint 缓存。本段的 biomes 换了、或横向邻段的 biomes 换了，都要调。 */
    void clearTints() {
        this.tints.clear();
    }

    /** 清整段。 */
    public void clearAll() {
        if (!this.eligible || this.count == 0) {
            return;
        }
        this.revision++;
        this.slots = null;
        this.count = 0;
        this.occupied = new int[0];
        this.occupiedCount = 0;
        this.farlands$publish();
        this.farlands$markDirty(0, 0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1,
                SimulationSection.SIZE - 1);
    }

    /**
     * 把活的字段发布成一份新的 {@link Content}。数组与光照层都取当前引用，因此调用方必须保证「当前引用之后
     * 不再被原地改」：{@link #set} 与 {@link #clear} 换新数组，{@link #setLight} 换新层。
     */
    private void farlands$publish() {
        int[] published = Arrays.copyOf(this.occupied, this.occupiedCount);
        this.content = new Content(this.slots, published, this.occupiedCount, this.blockLight, this.skyLight,
                this.biomes, this.shapeMode);
    }

    /** 占用表追加一项，返回新数组。 */
    private static int[] farlands$with(int index, int[] occupied, int count) {
        int[] next = Arrays.copyOf(occupied, count + 1);
        next[count] = index;
        return next;
    }

    /** 占用表摘掉一项，返回新数组。 */
    private static int[] farlands$without(int index, int[] occupied, int count) {
        int[] next = new int[Math.max(0, count - 1)];
        int written = 0;
        for (int i = 0; i < count; i++) {
            if (occupied[i] != index) {
                next[written++] = occupied[i];
            }
        }
        return next;
    }

    /** 取走脏标记与它的局部范围；干净时返回 null。 */
    public Dirty takeDirty() {
        if (!this.dirty) {
            return null;
        }
        this.dirty = false;
        Dirty range = new Dirty(this.dirtyMinX, this.dirtyMaxX, this.dirtyMinY, this.dirtyMaxY,
                this.dirtyMinZ, this.dirtyMaxZ);
        this.dirtyMinX = Integer.MAX_VALUE;
        this.dirtyMaxX = Integer.MIN_VALUE;
        this.dirtyMinY = Integer.MAX_VALUE;
        this.dirtyMaxY = Integer.MIN_VALUE;
        this.dirtyMinZ = Integer.MAX_VALUE;
        this.dirtyMaxZ = Integer.MIN_VALUE;
        return range;
    }

    /** 一次脏标记覆盖的段内局部范围。六个分量各自独立，三轴同形。 */
    public record Dirty(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    /** 记一次脏，并把它覆盖的局部坐标并进范围。三轴同形，逐轴取最小与最大。 */
    private void farlands$markDirty(int localX, int localY, int localZ) {
        if (localX < this.dirtyMinX) {
            this.dirtyMinX = localX;
        }
        if (localX > this.dirtyMaxX) {
            this.dirtyMaxX = localX;
        }
        if (localY < this.dirtyMinY) {
            this.dirtyMinY = localY;
        }
        if (localY > this.dirtyMaxY) {
            this.dirtyMaxY = localY;
        }
        if (localZ < this.dirtyMinZ) {
            this.dirtyMinZ = localZ;
        }
        if (localZ > this.dirtyMaxZ) {
            this.dirtyMaxZ = localZ;
        }
        this.dirty = true;
    }

    Simulation.SnapSection snap() {
        return this.snap;
    }

    /** 摘掉已发布的快照段，供段变空时从渲染列表里移除。 */
    void clearSnap() {
        this.snap = null;
    }

    /**
     * 重建快照段：只带本段的引用与键，不再算包围盒。
     *
     * <p>
     * 包围盒曾经是已占用格的并集，被碰撞与拾取当粗筛用。那套粗筛依赖「形状落在格内」，而派生形状不满足它，
     * 现在两处消费者都改成按格遍历、逐格取真实形状，不再需要这个超集。逐块对象仍不分配。
     */
    void rebuild() {
        if (this.count == 0) {
            this.snap = null;
            return;
        }
        this.snap = new Simulation.SnapSection(this.sectionX, this.sectionY, this.sectionZ,
                new Simulation.SectionKey(this.sectionX, this.sectionY, this.sectionZ), this);
    }

    /**
     * 两层光照是否同值。{@code DataLayer} 没有 {@code equals}，而「同质」层用默认值表示、不占数组，所以两侧
     * 都可能是 {@code null} 数组而默认值不同。这里先走同质层的判据，只有两侧都有数组时才逐字节比。
     */
    private static boolean farlands$sameLight(DataLayer a, DataLayer b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            DataLayer other = a == null ? b : a;
            return other == null || other.isEmpty();
        }
        boolean aEmpty = a.isEmpty();
        boolean bEmpty = b.isEmpty();
        if (aEmpty || bEmpty) {
            return aEmpty && bEmpty;
        }
        for (int value = 1; value < 16; value++) {
            boolean aFilled = a.isDefinitelyFilledWith(value);
            boolean bFilled = b.isDefinitelyFilledWith(value);
            if (aFilled || bFilled) {
                return aFilled && bFilled;
            }
        }
        return Arrays.equals(a.getData(), b.getData());
    }

    /** 两份群系容器是否逐格同值。64 格引用比较。 */
    private static boolean farlands$sameBiomes(PalettedContainerRO<Holder<Biome>> a,
            PalettedContainerRO<Holder<Biome>> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        for (int x = 0; x < QUART; x++) {
            for (int y = 0; y < QUART; y++) {
                for (int z = 0; z < QUART; z++) {
                    if (a.get(x, y, z) != b.get(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int farlands$index(int localX, int localY, int localZ) {
        if (((localX | localY | localZ) >>> 4) != 0) {
            throw new IllegalArgumentException(
                    "farlands: local coordinates out of a section: " + localX + ", " + localY + ", " + localZ);
        }
        return (localX << 8) | (localY << 4) | localZ;
    }

    private static boolean farlands$playable(long sectionCoord) {
        return sectionCoord >= WorldBounds.MIN_PLAYABLE_SECTION
                && sectionCoord <= WorldBounds.MAX_PLAYABLE_SECTION;
    }
}
