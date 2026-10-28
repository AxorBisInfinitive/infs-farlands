package com.inf.farlands.client.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.phys.AABB;

/**
 * 客户端的模拟方块层：生产者每 tick 写块，提交之后由渲染与碰撞两处统一消费，与服务端无关。
 *
 * <p>
 * 刻意不经过 {@code BlockPos}、{@code SectionPos} 与段编译。段键是三轴 long，块在段内是 0 到 15 的局部
 * 坐标，世界位置由 {@link #delta} 给出：段原点与相机整数部分都在 long 上相减，差值是小整数，落 float 精确。
 *
 * <p>
 * 段跨 tick 保留，生产者显式清除。可玩界内的段静默失效，见 {@link SimulationSection}。
 *
 * <p>
 * 线程：写与 {@link #commit} 只在客户端主线程；{@link #snapshot} 由渲染线程与碰撞读，发布的是不可变快照，
 * 每次提交整体换一次引用。
 */
public final class Simulation {

    private final Map<SectionKey, SimulationSection> sections = new HashMap<>();

    /** 段集合有增删时置真，提交要换快照。 */
    private boolean structureChanged;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** 取或建一段。可玩界内的段也会返回对象，只是写入被丢弃。 */
    public SimulationSection section(long sectionX, long sectionY, long sectionZ) {
        SectionKey key = new SectionKey(sectionX, sectionY, sectionZ);
        SimulationSection section = this.sections.get(key);
        if (section == null) {
            section = new SimulationSection(sectionX, sectionY, sectionZ);
            this.sections.put(key, section);
            this.structureChanged = true;
        }
        return section;
    }

    /** 摘掉一段。 */
    public void removeSection(long sectionX, long sectionY, long sectionZ) {
        if (this.sections.remove(new SectionKey(sectionX, sectionY, sectionZ)) != null) {
            this.structureChanged = true;
        }
    }

    /** 全部清空。卸关卡时调用。 */
    public void clear() {
        this.sections.clear();
        this.structureChanged = true;
        this.snapshot = Snapshot.EMPTY;
    }

    public int sectionCount() {
        return this.sections.size();
    }

    /** 当前快照，任意线程可读。 */
    public Snapshot snapshot() {
        return this.snapshot;
    }

    /**
     * 把脏段重建并发布新快照，主线程每 tick 一次。
     *
     * <p>
     * 只在有段变脏或段集合有增删时换快照，其余时候一次也不发布。
     */
    public void commit() {
        boolean changed = this.structureChanged;
        this.structureChanged = false;
        List<SnapSection> out = new ArrayList<>(this.sections.size());
        for (SimulationSection section : this.sections.values()) {
            if (!section.eligible()) {
                continue;
            }
            if (section.takeDirty()) {
                section.rebuild();
                changed = true;
            }
            SnapSection snap = section.snap();
            if (snap != null) {
                out.add(snap);
            }
        }
        if (changed) {
            this.snapshot = new Snapshot(List.copyOf(out));
        }
    }

    /**
     * 世界坐标减相机坐标，精度靠 long 保证。
     *
     * <p>
     * 段原点先与相机的整数部分在 long 上相减，得到小整数；小数部分单独补。直接做
     * {@code (double) origin - camera} 在大坐标上会把两量各自量化，块与块就此糊在一起。
     */
    public static double delta(long origin, double camera) {
        long whole = (long) Math.floor(camera);
        return (double) (origin - whole) + (whole - camera);
    }

    /** 段键，三轴 long。 */
    record SectionKey(long x, long y, long z) {
    }

    /** 一次发布的不可变快照。 */
    public static final class Snapshot {

        public static final Snapshot EMPTY = new Snapshot(List.of());

        private final List<SnapSection> sections;

        Snapshot(List<SnapSection> sections) {
            this.sections = sections;
        }

        public List<SnapSection> sections() {
            return this.sections;
        }
    }

    /** 快照里的一段：段原点的 long、段局部包围盒、非空块。 */
    public record SnapSection(long sectionX, long sectionY, long sectionZ, AABB localBounds,
            List<SnapBlock> blocks) {

        /** 世界坐标的段原点。 */
        public long originX() {
            return this.sectionX * SimulationSection.SIZE;
        }

        public long originY() {
            return this.sectionY * SimulationSection.SIZE;
        }

        public long originZ() {
            return this.sectionZ * SimulationSection.SIZE;
        }
    }

    /** 快照里的一个块：段内局部坐标与方块。 */
    public record SnapBlock(int localX, int localY, int localZ, SimulationBlock block) {
    }
}
