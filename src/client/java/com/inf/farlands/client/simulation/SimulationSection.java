package com.inf.farlands.client.simulation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.util.world.WorldBounds;

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
 * 写入只在客户端主线程发生；读的一侧是提交之后发布的 {@link Simulation.SnapSection}，本类自身不被渲染
 * 线程触碰。
 */
public final class SimulationSection {

    /** 段边长。 */
    public static final int SIZE = 16;

    /** 每轴 quart 格数，即群系网格的边长。 */
    public static final int QUART = SIZE / 4;

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
        this.blockLight.set(localX, localY, localZ, block);
        this.skyLight.set(localX, localY, localZ, sky);
        this.farlands$markDirty(localX, localY, localZ);
    }

    /** 整层同值。 */
    public void fillLight(int block, int sky) {
        this.blockLight.fill(block);
        this.skyLight.fill(sky);
        this.farlands$markDirty(0, 0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1,
                SimulationSection.SIZE - 1);
    }

    /** 该段是否参与模拟。可玩界内的段为假。 */
    public boolean eligible() {
        return this.eligible;
    }

    /**
     * 用服务端送来的一份内容整体替换本段：块、群系、两层光照与形状模式。
     *
     * <p>
     * 失效段直接返回：可玩界内的段不参与模拟，服务端也不该发它们。
     *
     * <p>
     * 模拟方块按 {@code BlockState} 建小表复用：同一状态共用一个实例，既省分配，也因为 {@link #set} 的
     * {@code previous == block} 是引用比较。
     *
     * <p>
     * 只在客户端主线程调，与包的分发点同线程。
     */
    public void applyContainer(LevelChunkSection container, int shapeMode, DataLayer blockLight, DataLayer skyLight) {
        if (!this.eligible) {
            return;
        }
        this.revision++;
        this.slots = null;
        this.count = 0;
        this.occupiedCount = 0;
        this.biomes = container.getBiomes();
        this.clearTints();
        this.blockLight = blockLight == null ? new DataLayer() : blockLight;
        this.skyLight = skyLight == null ? new DataLayer() : skyLight;
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
                    if (this.slots == null) {
                        this.slots = new SimulationBlock[SIZE * SIZE * SIZE];
                    }
                    this.slots[index] = block;
                    this.count++;
                    this.farlands$occupy(index);
                }
            }
        }
        this.farlands$markDirty(0, 0, 0);
        this.farlands$markDirty(SIZE - 1, SIZE - 1, SIZE - 1);
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
        this.occupiedCount = 0;
        this.snap = null;
        this.biomes = container.getBiomes();
        this.clearTints();
        this.blockLight = blockLight == null ? new DataLayer() : blockLight;
        this.skyLight = skyLight == null ? new DataLayer() : skyLight;
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
        if (block == null) {
            if (current == null) {
                return;
            }
            current[index] = null;
            this.count--;
            this.farlands$vacate(index);
        } else {
            if (current == null) {
                current = new SimulationBlock[SIZE * SIZE * SIZE];
                this.slots = current;
            }
            if (previous == null) {
                this.count++;
                this.farlands$occupy(index);
            }
            current[index] = block;
        }
        this.revision++;
        this.farlands$markDirty(localX, localY, localZ);
    }

    /** 清一格。 */
    public void clear(int localX, int localY, int localZ) {
        this.set(localX, localY, localZ, null);
    }

    /** 读一格，供烘几何与命中用。局部坐标必须在 0 到 15 内。 */
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

    /**
     * 非空格下标的拷贝，供烘焙快照用。
     *
     * <p>
     * {@link #occupied} 在原数组上增删，工作线程共享引用会读到写了一半的计数与下标，所以快照必须拷前缀。
     */
    int[] occupiedSnapshot() {
        return Arrays.copyOf(this.occupied, this.occupiedCount);
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
        this.occupiedCount = 0;
        this.farlands$markDirty(0, 0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1,
                SimulationSection.SIZE - 1);
    }

    private void farlands$occupy(int index) {
        if (this.occupiedCount == this.occupied.length) {
            this.occupied = Arrays.copyOf(this.occupied, this.occupied.length * 2);
        }
        this.occupied[this.occupiedCount++] = index;
    }

    /** 摘除用尾元素顶上，不保序，省一次搬移。 */
    private void farlands$vacate(int index) {
        for (int i = 0; i < this.occupiedCount; i++) {
            if (this.occupied[i] == index) {
                this.occupied[i] = this.occupied[--this.occupiedCount];
                return;
            }
        }
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
