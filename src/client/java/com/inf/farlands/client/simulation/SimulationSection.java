package com.inf.farlands.client.simulation;

import java.util.Arrays;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.Holder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.phys.AABB;

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
    private final DataLayer skyLight = new DataLayer();
    private final DataLayer blockLight = new DataLayer();

    private final SimulationBlock[] slots = new SimulationBlock[SIZE * SIZE * SIZE];

    private int count;

    private boolean dirty;

    /** 脏范围的段内局部坐标，供重烘与邻列失效用。干净时 min 大于 max。 */
    private int dirtyMinX = Integer.MAX_VALUE;
    private int dirtyMaxX = Integer.MIN_VALUE;
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
        this.farlands$markDirty(quartX * 4, quartZ * 4);
        this.farlands$markDirty(quartX * 4 + 3, quartZ * 4 + 3);
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
        this.farlands$markDirty(0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1);
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
        this.farlands$markDirty(localX, localZ);
    }

    /** 整层同值。 */
    public void fillLight(int block, int sky) {
        this.blockLight.fill(block);
        this.skyLight.fill(sky);
        this.farlands$markDirty(0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1);
    }

    /** 该段是否参与模拟。可玩界内的段为假。 */
    public boolean eligible() {
        return this.eligible;
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
        SimulationBlock previous = this.slots[index];
        if (previous == block) {
            return;
        }
        if (previous == null) {
            this.count++;
            this.farlands$occupy(index);
        } else if (block == null) {
            this.count--;
            this.farlands$vacate(index);
        }
        this.slots[index] = block;
        this.farlands$markDirty(localX, localZ);
    }

    /** 清一格。 */
    public void clear(int localX, int localY, int localZ) {
        this.set(localX, localY, localZ, null);
    }

    /** 读一格，供烘几何与命中用。局部坐标必须在 0 到 15 内。 */
    public SimulationBlock blockAt(int localX, int localY, int localZ) {
        return this.slots[farlands$index(localX, localY, localZ)];
    }

    /** 非空格的数量。 */
    public int occupiedCount() {
        return this.occupiedCount;
    }

    /** 第 i 个非空格的下标，形如 {@code (x << 8) | (y << 4) | z}。 */
    public int occupiedIndex(int i) {
        return this.occupied[i];
    }

    /** 清整段。 */
    public void clearAll() {
        if (!this.eligible || this.count == 0) {
            return;
        }
        Arrays.fill(this.slots, null);
        this.count = 0;
        this.occupiedCount = 0;
        this.farlands$markDirty(0, 0);
        this.farlands$markDirty(SimulationSection.SIZE - 1, SimulationSection.SIZE - 1);
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
        Dirty range = new Dirty(this.dirtyMinX, this.dirtyMaxX, this.dirtyMinZ, this.dirtyMaxZ);
        this.dirtyMinX = Integer.MAX_VALUE;
        this.dirtyMaxX = Integer.MIN_VALUE;
        this.dirtyMinZ = Integer.MAX_VALUE;
        this.dirtyMaxZ = Integer.MIN_VALUE;
        return range;
    }

    /** 一次脏标记覆盖的段内局部范围。 */
    public record Dirty(int minX, int maxX, int minZ, int maxZ) {
    }

    /** 记一次脏，并把它覆盖的局部坐标并进范围。 */
    private void farlands$markDirty(int localX, int localZ) {
        if (localX < this.dirtyMinX) {
            this.dirtyMinX = localX;
        }
        if (localX > this.dirtyMaxX) {
            this.dirtyMaxX = localX;
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
     * 重建快照段：段局部包围盒，以及指向本段的引用。
     *
     * <p>
     * 不再为每个非空块造一条记录：渲染读显存里的段几何，碰撞与拾取按 {@link #occupiedIndex} 现取，所以快照只需
     * 一段引用与包围盒。逐块对象的分配因此从提交路径上去掉。
     */
    /**
     * 重建快照段：段局部的格并集包围盒，以及指向本段的引用。
     *
     * <p>
     * 包围盒取已占用格的并集，不取形状的并集：形状按用途派生，可能依赖邻块状态，本方法没有 getter；而格并集对
     * 「形状落在格内」的任何形状都是超集，作为粗筛合法。逐块对象仍不分配。
     */
    void rebuild() {
        if (this.count == 0) {
            this.snap = null;
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < this.occupiedCount; i++) {
            int index = this.occupied[i];
            if (this.slots[index] == null) {
                continue;
            }
            int localX = (index >> 8) & 15;
            int localY = (index >> 4) & 15;
            int localZ = index & 15;
            if (localX < minX) {
                minX = localX;
            }
            if (localY < minY) {
                minY = localY;
            }
            if (localZ < minZ) {
                minZ = localZ;
            }
            if (localX > maxX) {
                maxX = localX;
            }
            if (localY > maxY) {
                maxY = localY;
            }
            if (localZ > maxZ) {
                maxZ = localZ;
            }
        }
        this.snap = new Simulation.SnapSection(this.sectionX, this.sectionY, this.sectionZ,
                new Simulation.SectionKey(this.sectionX, this.sectionY, this.sectionZ),
                new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0), this);
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
