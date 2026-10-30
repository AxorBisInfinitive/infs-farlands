package com.inf.farlands.client.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
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
 * 命中按形状本身算：先取形状各轴的最小与最大坐标做粗筛，再对命中的块逐盒精修，所以半砖的空处不会命中，凹形也
 * 不会比真实形状宽松。粗筛用的是形状自己的包围盒，不假设形状落在块内；空形状先跳过，因为空形状的 {@code min} 是
 * {@code +Infinity}、{@code max} 是 {@code -Infinity}，直接喂进裁剪会变成无限盒。段级粗筛仍按段立方，形状越出所在段时那一筛会把
 * 整段跳过，这条假设由生产者遵守。
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
     * <p>
     * 形状来源与 vanilla 的 {@code ClipContext.OUTLINE} 一致：块上有轮廓覆盖就用它，否则走
     * {@code state.getShape}。getter 由 {@code simulation} 构造，形状可能查邻块，所以要能跨段。
     *
     * @param simulation 模拟层，用于构造跨段的取数入口
     * @param eye        眼睛的世界坐标
     * @param view       单位视线向量
     * @param reach      距离上限，判据是严格小于
     */
    public static Hit pick(Simulation.Snapshot snapshot, Simulation simulation, Vec3 eye, Vec3 view, double reach) {
        Hit best = null;
        double bestT = reach;
        if (reach <= 0.0) {
            return null;
        }
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        for (Simulation.SnapSection section : snapshot.sections()) {
            double baseX = Simulation.delta(section.originX(), eye.x());
            double baseY = Simulation.delta(section.originY(), eye.y());
            double baseZ = Simulation.delta(section.originZ(), eye.z());
            double sectionT = enter(view, baseX, baseY, baseZ,
                    baseX + SimulationSection.SIZE, baseY + SimulationSection.SIZE, baseZ + SimulationSection.SIZE);
            if (Double.isNaN(sectionT) || sectionT >= bestT) {
                continue;
            }
            SimulationSection live = section.section();
            SectionGetter getter = new SectionGetter(live, simulation);
            int occupied = live.occupiedCount();
            for (int i = 0; i < occupied; i++) {
                int index = live.occupiedIndex(i);
                int localX = (index >> 8) & 15;
                int localY = (index >> 4) & 15;
                int localZ = index & 15;
                SimulationBlock block = live.blockAt(localX, localY, localZ);
                if (block == null) {
                    continue;
                }
                localPos.set(localX, localY, localZ);
                VoxelShape shape = block.outlineShape();
                if (shape == null) {
                    shape = block.state().getShape(getter, localPos);
                }
                if (shape.isEmpty()) {
                    continue;
                }
                double ox = baseX + localX;
                double oy = baseY + localY;
                double oz = baseZ + localZ;
                double coarse = enter(view,
                        ox + shape.min(Direction.Axis.X), oy + shape.min(Direction.Axis.Y),
                        oz + shape.min(Direction.Axis.Z),
                        ox + shape.max(Direction.Axis.X), oy + shape.max(Direction.Axis.Y),
                        oz + shape.max(Direction.Axis.Z));
                if (Double.isNaN(coarse) || coarse >= bestT) {
                    continue;
                }
                double t = Double.POSITIVE_INFINITY;
                for (AABB box : shape.toAabbs()) {
                    double hit = enter(view, ox + box.minX, oy + box.minY, oz + box.minZ,
                            ox + box.maxX, oy + box.maxY, oz + box.maxZ);
                    if (!Double.isNaN(hit) && hit < t) {
                        t = hit;
                    }
                }
                if (t >= bestT) {
                    continue;
                }
                bestT = t;
                best = new Hit(section.originX(), section.originY(), section.originZ(),
                        localX, localY, localZ, shape);
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
