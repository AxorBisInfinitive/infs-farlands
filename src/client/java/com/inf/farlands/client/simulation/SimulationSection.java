package com.inf.farlands.client.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 一段 16 立方的模拟方块，坐标为段坐标，三轴都是 long。
 *
 * <p>
 * 三轴段坐标都落在可玩段界之内的段是失效段：{@link #set} 直接返回，不写槽也不记脏。这样的段不进快照，
 * 于是既不渲染也不参与碰撞，且不打日志。判据取可玩段界而不是可表示段界，因为可表示界两端之外还有一整段
 * 可玩的方块在界内，那些段要继续工作。
 *
 * <p>
 * 写入只在客户端主线程发生；读的一侧是提交之后发布的 {@link Simulation.SnapSection}，本类自身不被渲染
 * 线程触碰。
 */
public final class SimulationSection {

    /** 段边长。 */
    public static final int SIZE = 16;

    private final long sectionX;
    private final long sectionY;
    private final long sectionZ;

    /** 可玩界外为真，界内为假。界内的段静默失效。 */
    private final boolean eligible;

    private final SimulationBlock[] slots = new SimulationBlock[SIZE * SIZE * SIZE];

    private int count;

    private boolean dirty;

    /** 最近一次重建出来的快照段，空段为 null。 */
    private Simulation.SnapSection snap;

    SimulationSection(long sectionX, long sectionY, long sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.eligible = !(farlands$playable(sectionX) && farlands$playable(sectionY)
                && farlands$playable(sectionZ));
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
        } else if (block == null) {
            this.count--;
        }
        this.slots[index] = block;
        this.dirty = true;
    }

    /** 清一格。 */
    public void clear(int localX, int localY, int localZ) {
        this.set(localX, localY, localZ, null);
    }

    /** 清整段。 */
    public void clearAll() {
        if (!this.eligible || this.count == 0) {
            return;
        }
        Arrays.fill(this.slots, null);
        this.count = 0;
        this.dirty = true;
    }

    /** 取走脏标记。 */
    boolean takeDirty() {
        boolean was = this.dirty;
        this.dirty = false;
        return was;
    }

    Simulation.SnapSection snap() {
        return this.snap;
    }

    /**
     * 重建快照段：非空块表与段局部包围盒。
     *
     * <p>
     * 只在这里遍历 4096 槽，且只在脏的时候，逐块取 {@code shape.bounds()} 求并。形状本身不合并，合并
     * 推迟到碰撞查询命中的那几块上。
     */
    void rebuild() {
        if (this.count == 0) {
            this.snap = null;
            return;
        }
        List<Simulation.SnapBlock> blocks = new ArrayList<>(this.count);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < this.slots.length; index++) {
            SimulationBlock block = this.slots[index];
            if (block == null) {
                continue;
            }
            int localX = (index >> 8) & 15;
            int localY = (index >> 4) & 15;
            int localZ = index & 15;
            blocks.add(new Simulation.SnapBlock(localX, localY, localZ, block));
            VoxelShape shape = block.shape();
            AABB bounds = shape.bounds().move(localX, localY, localZ);
            if (bounds.minX < minX) {
                minX = bounds.minX;
            }
            if (bounds.minY < minY) {
                minY = bounds.minY;
            }
            if (bounds.minZ < minZ) {
                minZ = bounds.minZ;
            }
            if (bounds.maxX > maxX) {
                maxX = bounds.maxX;
            }
            if (bounds.maxY > maxY) {
                maxY = bounds.maxY;
            }
            if (bounds.maxZ > maxZ) {
                maxZ = bounds.maxZ;
            }
        }
        this.snap = new Simulation.SnapSection(this.sectionX, this.sectionY, this.sectionZ,
                new AABB(minX, minY, minZ, maxX, maxY, maxZ), List.copyOf(blocks));
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
