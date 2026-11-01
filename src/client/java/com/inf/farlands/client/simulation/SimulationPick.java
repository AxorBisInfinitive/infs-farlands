package com.inf.farlands.client.simulation;

import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
 * 眼睛不取 {@code Vec3}：世界量级的 double 在 {@code 5e18} 处 ulp 是 1024，{@code eye + view * reach} 里的 reach
 * 会被整块吞掉。这里从实体的帧拿「锚点 long 加局部 double」，段内起点按
 * {@code 局部 - (段原点 - 锚点)} 算，reach 量级的位移因此一路精确。
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
     * 段原点与块内局部坐标分开给：画框那一侧要用相机帧把原点折成相机相对量，局部坐标则是形状的平移量。
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
     * @param entity     眼睛所属实体，帧与眼高都向它要
     * @param partial    部分刻，与原点插值同源
     * @param view       单位视线向量
     * @param reach      视线长度
     */
    public static Hit pick(Simulation simulation, Entity entity, float partial, Vec3 view, double reach) {
        if (reach <= 0.0) {
            return null;
        }
        ExactPosition holder = (ExactPosition) entity;
        long eyeAnchorX = holder.farlands$anchor(ExactPosition.POS_X);
        long eyeAnchorY = holder.farlands$anchor(ExactPosition.POS_Y);
        long eyeAnchorZ = holder.farlands$anchor(ExactPosition.POS_Z);
        double eyeLocalX = holder.farlands$lerpLocal(ExactPosition.POS_X, partial);
        double eyeLocalY = holder.farlands$lerpLocal(ExactPosition.POS_Y, partial) + (double) entity.getEyeHeight();
        double eyeLocalZ = holder.farlands$lerpLocal(ExactPosition.POS_Z, partial);
        long stepX = (long) Math.floor(eyeLocalX);
        long stepY = (long) Math.floor(eyeLocalY);
        long stepZ = (long) Math.floor(eyeLocalZ);
        eyeAnchorX += stepX;
        eyeAnchorY += stepY;
        eyeAnchorZ += stepZ;
        eyeLocalX -= (double) stepX;
        eyeLocalY -= (double) stepY;
        eyeLocalZ -= (double) stepZ;
        Vec3 delta = view.scale(reach);
        long startSectionX = Math.floorDiv(eyeAnchorX, (long) SimulationSection.SIZE);
        long startSectionY = Math.floorDiv(eyeAnchorY, (long) SimulationSection.SIZE);
        long startSectionZ = Math.floorDiv(eyeAnchorZ, (long) SimulationSection.SIZE);
        double localEyeX = localIn(startSectionX * SimulationSection.SIZE, eyeAnchorX, eyeLocalX);
        double localEyeY = localIn(startSectionY * SimulationSection.SIZE, eyeAnchorY, eyeLocalY);
        double localEyeZ = localIn(startSectionZ * SimulationSection.SIZE, eyeAnchorZ, eyeLocalZ);
        double localToX = localEyeX + delta.x;
        double localToY = localEyeY + delta.y;
        double localToZ = localEyeZ + delta.z;
        Cursor cursor = new Cursor(simulation, eyeAnchorX, eyeAnchorY, eyeAnchorZ, eyeLocalX, eyeLocalY, eyeLocalZ,
                delta);
        double toX = Mth.lerp(NUDGE, localToX, localEyeX);
        double toY = Mth.lerp(NUDGE, localToY, localEyeY);
        double toZ = Mth.lerp(NUDGE, localToZ, localEyeZ);
        double fromX = Mth.lerp(NUDGE, localEyeX, localToX);
        double fromY = Mth.lerp(NUDGE, localEyeY, localToY);
        double fromZ = Mth.lerp(NUDGE, localEyeZ, localToZ);
        long currentX = startSectionX * SimulationSection.SIZE + Mth.lfloor(fromX);
        long currentY = startSectionY * SimulationSection.SIZE + Mth.lfloor(fromY);
        long currentZ = startSectionZ * SimulationSection.SIZE + Mth.lfloor(fromZ);
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

    /** 段内坐标：局部量减「段原点相对锚点的位置」，两个量都是小整数加小数，精确。 */
    private static double localIn(long sectionOrigin, long anchor, double local) {
        return local - (double) (sectionOrigin - anchor);
    }

    /**
     * 逐格取形状的上下文：缓存当前段、它的 getter 与段内起点终点，跨段时才重建。
     */
    private static final class Cursor {

        private final Simulation simulation;
        private final long eyeAnchorX;
        private final long eyeAnchorY;
        private final long eyeAnchorZ;
        private final double eyeLocalX;
        private final double eyeLocalY;
        private final double eyeLocalZ;
        private final Vec3 delta;
        private final BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();

        private SimulationSection section;
        private SectionGetter getter;
        private long sectionX;
        private long sectionY;
        private long sectionZ;
        private boolean hasSection;
        private Vec3 localFrom;
        private Vec3 localTo;

        Cursor(Simulation simulation, long eyeAnchorX, long eyeAnchorY, long eyeAnchorZ, double eyeLocalX,
                double eyeLocalY, double eyeLocalZ, Vec3 delta) {
            this.simulation = simulation;
            this.eyeAnchorX = eyeAnchorX;
            this.eyeAnchorY = eyeAnchorY;
            this.eyeAnchorZ = eyeAnchorZ;
            this.eyeLocalX = eyeLocalX;
            this.eyeLocalY = eyeLocalY;
            this.eyeLocalZ = eyeLocalZ;
            this.delta = delta;
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
                this.localFrom = new Vec3(
                        localIn(sx * SimulationSection.SIZE, this.eyeAnchorX, this.eyeLocalX),
                        localIn(sy * SimulationSection.SIZE, this.eyeAnchorY, this.eyeLocalY),
                        localIn(sz * SimulationSection.SIZE, this.eyeAnchorZ, this.eyeLocalZ));
                this.localTo = new Vec3(this.localFrom.x + this.delta.x, this.localFrom.y + this.delta.y,
                        this.localFrom.z + this.delta.z);
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
