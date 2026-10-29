package com.inf.farlands.client.simulation;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 在模拟方块层的快照上做视线命中，结果只给选择框用，不参与任何交互。
 *
 * <p>
 * 命中在眼睛相对的坐标系里做：段原点是 long，与眼睛的整数部分先相减得到小整数，再补小数部分，于是块盒的坐标
 * 都落在眼睛附近，双精度足够分辨一格。直接拿绝对坐标相减会把两个大数各自量化，块与块挤成一片。
 *
 * <p>
 * 判据与 vanilla 的方块命中同形：从眼睛出发、方向为单位向量、距离严格小于 reach，取最近的一块。快照里没有
 * 实体，所以没有实体那一路的竞争。
 *
 * <p>
 * 命中按形状的包围盒算。整格形状时包围盒就是形状本身，与 vanilla 逐值相同；非整格或凹形形状会略微宽松。
 *
 * <p>
 * 纯函数，无状态。
 */
public final class SimulationPick {

    /** 方向某一轴小于此值时按平行处理，避免除出无穷。 */
    private static final double PARALLEL = 1.0E-9;

    /**
     * 一次命中。
     *
     * <p>
     * 段原点与块内局部坐标分开给：画框那一侧要用 {@link Simulation#delta} 把原点换成相机相对量，局部坐标
     * 则是形状的平移量。
     */
    public record Hit(long originX, long originY, long originZ, int localX, int localY, int localZ,
            VoxelShape shape) {
    }

    private SimulationPick() {
    }

    /**
     * 取视线命中的那一块，没有命中返回 null。
     *
     * @param eye   眼睛的世界坐标
     * @param view  单位视线向量
     * @param reach 距离上限，判据是严格小于
     */
    public static Hit pick(Simulation.Snapshot snapshot, Vec3 eye, Vec3 view, double reach) {
        Hit best = null;
        double bestT = reach;
        if (reach <= 0.0) {
            return null;
        }
        for (Simulation.SnapSection section : snapshot.sections()) {
            double baseX = Simulation.delta(section.originX(), eye.x());
            double baseY = Simulation.delta(section.originY(), eye.y());
            double baseZ = Simulation.delta(section.originZ(), eye.z());
            double sectionT = enter(view, baseX, baseY, baseZ,
                    baseX + SimulationSection.SIZE, baseY + SimulationSection.SIZE, baseZ + SimulationSection.SIZE);
            if (Double.isNaN(sectionT) || sectionT >= bestT) {
                continue;
            }
            for (Simulation.SnapBlock block : section.blocks()) {
                double minX = baseX + block.localX();
                double minY = baseY + block.localY();
                double minZ = baseZ + block.localZ();
                double t = enter(view, minX, minY, minZ, minX + 1.0, minY + 1.0, minZ + 1.0);
                if (Double.isNaN(t) || t >= bestT) {
                    continue;
                }
                bestT = t;
                best = new Hit(section.originX(), section.originY(), section.originZ(),
                        block.localX(), block.localY(), block.localZ(), block.block().shape());
            }
        }
        return best;
    }

    /** 射线与盒的进入距离。射线起点是原点，三轴都从 0 起算。未命中返 {@code NaN}。 */
    private static double enter(Vec3 view, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        double[] range = { 0.0, Double.POSITIVE_INFINITY };
        if (!slab(view.x, minX, maxX, range)) {
            return Double.NaN;
        }
        if (!slab(view.y, minY, maxY, range)) {
            return Double.NaN;
        }
        if (!slab(view.z, minZ, maxZ, range)) {
            return Double.NaN;
        }
        return range[0] <= range[1] ? range[0] : Double.NaN;
    }

    /** 单轴 slabs 裁剪。起点恒为 0，所以平行轴只判原点是否落在区间内。 */
    private static boolean slab(double dir, double min, double max, double[] range) {
        if (Math.abs(dir) < PARALLEL) {
            return min <= 0.0 && 0.0 <= max;
        }
        double t1 = min / dir;
        double t2 = max / dir;
        if (t1 > t2) {
            double swap = t1;
            t1 = t2;
            t2 = swap;
        }
        if (t1 > range[0]) {
            range[0] = t1;
        }
        if (t2 < range[1]) {
            range[1] = t2;
        }
        return range[0] <= range[1];
    }
}
