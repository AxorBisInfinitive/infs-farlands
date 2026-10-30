package com.inf.farlands.client.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 在模拟方块层上做视线命中，结果只给选择框用，不参与任何交互。
 *
 * <p>
 * 遍历照抄 vanilla 的 {@code BlockGetter.clip}：{@code traverseBlocks} 的 DDA 步进，从眼睛所在格开始按射线
 * 方向逐格推进，命中即返回，不做距离比较。端点各按 {@code 1.0E-7} 向起点 nudged 一次，与 vanilla 同。
 *
 * <p>
 * 每格按 {@code ClipContext.OUTLINE} 的口径取形状：块上有轮廓覆盖就用它，否则走 {@code state.getShape}；空
 * 形状跳过。判交交给 {@code VoxelShape.clip}，即精确形状裁剪，不假设形状落在格内。
 *
 * <p>
 * 坐标换算：格坐标用 long，{@code VoxelShape.clip} 的入参与 {@code BlockPos} 折算到该格所在段的局部空间。
 * 世界坐标在 {@code BlockPos} 上饱和，所以这一步不能省。段与段内起点在跨段时才重算。
 *
 * <p>
 * 纯函数，无状态。
 */
public final class SimulationPick {

    /** 与 vanilla {@code traverseBlocks} 的 nudge 一致，端点恰在格边界时不至于少走一格。 */
    private static final double NUDGE = -1.0E-7;

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
     * @param simulation 模拟层，用于构造跨段的取数入口
     * @param eye        眼睛的世界坐标
     * @param view       单位视线向量
     * @param reach      视线长度
     */
    public static Hit pick(Simulation simulation, Vec3 eye, Vec3 view, double reach) {
        if (reach <= 0.0) {
            return null;
        }
        Vec3 to = eye.add(view.scale(reach));
        Cursor cursor = new Cursor(simulation, eye, to);
        double toX = Mth.lerp(NUDGE, to.x, eye.x);
        double toY = Mth.lerp(NUDGE, to.y, eye.y);
        double toZ = Mth.lerp(NUDGE, to.z, eye.z);
        double fromX = Mth.lerp(NUDGE, eye.x, to.x);
        double fromY = Mth.lerp(NUDGE, eye.y, to.y);
        double fromZ = Mth.lerp(NUDGE, eye.z, to.z);
        long currentX = Mth.lfloor(fromX);
        long currentY = Mth.lfloor(fromY);
        long currentZ = Mth.lfloor(fromZ);
        Hit hit = cursor.at(currentX, currentY, currentZ);
        if (hit != null) {
            return hit;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        int signX = Mth.sign(dx);
        int signY = Mth.sign(dy);
        int signZ = Mth.sign(dz);
        double tDeltaX = signX == 0 ? Double.MAX_VALUE : (double) signX / dx;
        double tDeltaY = signY == 0 ? Double.MAX_VALUE : (double) signY / dy;
        double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : (double) signZ / dz;
        double tX = tDeltaX * (signX > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
        double tY = tDeltaY * (signY > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    currentX += signX;
                    tX += tDeltaX;
                } else {
                    currentZ += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                currentY += signY;
                tY += tDeltaY;
            } else {
                currentZ += signZ;
                tZ += tDeltaZ;
            }
            hit = cursor.at(currentX, currentY, currentZ);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 逐格取形状的上下文：缓存当前段、它的 getter 与段内起点终点，跨段时才重建。
     *
     * <p>
     * 段内那三组量都是「世界减段原点」，差值是 reach 量级的小数，double 精确。
     */
    private static final class Cursor {

        private final Simulation simulation;
        private final Vec3 eye;
        private final Vec3 to;
        private final BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();

        private SimulationSection section;
        private SectionGetter getter;
        private long sectionX;
        private long sectionY;
        private long sectionZ;
        private boolean hasSection;
        private Vec3 localFrom;
        private Vec3 localTo;

        Cursor(Simulation simulation, Vec3 eye, Vec3 to) {
            this.simulation = simulation;
            this.eye = eye;
            this.to = to;
        }

        /** 该格上的命中；格不属于任何模拟段、为空、形状为空或射线不穿过形状时返回 null。 */
        Hit at(long x, long y, long z) {
            long sx = Math.floorDiv(x, (long) SimulationSection.SIZE);
            long sy = Math.floorDiv(y, (long) SimulationSection.SIZE);
            long sz = Math.floorDiv(z, (long) SimulationSection.SIZE);
            if (!this.hasSection || sx != this.sectionX || sy != this.sectionY || sz != this.sectionZ) {
                this.hasSection = true;
                this.sectionX = sx;
                this.sectionY = sy;
                this.sectionZ = sz;
                this.section = this.simulation.sectionOrNull(sx, sy, sz);
                this.getter = this.section == null ? null : new SectionGetter(this.section, this.simulation);
                double originX = (double) (sx * SimulationSection.SIZE);
                double originY = (double) (sy * SimulationSection.SIZE);
                double originZ = (double) (sz * SimulationSection.SIZE);
                this.localFrom = new Vec3(this.eye.x - originX, this.eye.y - originY, this.eye.z - originZ);
                this.localTo = new Vec3(this.to.x - originX, this.to.y - originY, this.to.z - originZ);
            }
            if (this.section == null) {
                return null;
            }
            int localX = (int) (x - this.sectionX * SimulationSection.SIZE);
            int localY = (int) (y - this.sectionY * SimulationSection.SIZE);
            int localZ = (int) (z - this.sectionZ * SimulationSection.SIZE);
            SimulationBlock block = this.section.blockAt(localX, localY, localZ);
            if (block == null) {
                return null;
            }
            this.localPos.set(localX, localY, localZ);
            VoxelShape shape = block.outlineShape();
            if (shape == null) {
                shape = block.state().getShape(this.getter, this.localPos);
            }
            if (shape.isEmpty()) {
                return null;
            }
            BlockHitResult hit = shape.clip(this.localFrom, this.localTo, this.localPos);
            if (hit == null) {
                return null;
            }
            return new Hit(this.sectionX * SimulationSection.SIZE, this.sectionY * SimulationSection.SIZE,
                    this.sectionZ * SimulationSection.SIZE, localX, localY, localZ, shape);
        }
    }
}
