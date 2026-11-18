package com.inf.farlands.client.simulation;

import love.crysh.long2.ints.Int1024;
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
    public record Hit(Int1024 originX, Int1024 originY, Int1024 originZ, int localX, int localY, int localZ,
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
        Int1024 eyeAnchorX = holder.farlands$anchor(ExactPosition.POS_X);
        Int1024 eyeAnchorY = holder.farlands$anchor(ExactPosition.POS_Y);
        Int1024 eyeAnchorZ = holder.farlands$anchor(ExactPosition.POS_Z);
        double eyeLocalX = holder.farlands$lerpLocal(ExactPosition.POS_X, partial);
        double eyeLocalY = holder.farlands$lerpLocal(ExactPosition.POS_Y, partial) + (double) entity.getEyeHeight();
        double eyeLocalZ = holder.farlands$lerpLocal(ExactPosition.POS_Z, partial);
        long stepX = (long) Math.floor(eyeLocalX);
        long stepY = (long) Math.floor(eyeLocalY);
        long stepZ = (long) Math.floor(eyeLocalZ);
        Int1024 blockX = eyeAnchorX.add(Int1024.of(stepX));
        Int1024 blockY = eyeAnchorY.add(Int1024.of(stepY));
        Int1024 blockZ = eyeAnchorZ.add(Int1024.of(stepZ));
        eyeLocalX -= (double) stepX;
        eyeLocalY -= (double) stepY;
        eyeLocalZ -= (double) stepZ;
        Vec3 delta = view.scale(reach);
        Int1024 startSectionX = blockX.shift(-4);
        Int1024 startSectionY = blockY.shift(-4);
        Int1024 startSectionZ = blockZ.shift(-4);
        double localEyeX = localIn(startSectionX.shift(4), blockX, eyeLocalX);
        double localEyeY = localIn(startSectionY.shift(4), blockY, eyeLocalY);
        double localEyeZ = localIn(startSectionZ.shift(4), blockZ, eyeLocalZ);
        double localToX = localEyeX + delta.x;
        double localToY = localEyeY + delta.y;
        double localToZ = localEyeZ + delta.z;
        Cursor cursor = new Cursor(simulation, startSectionX, startSectionY, startSectionZ, blockX, blockY, blockZ,
                eyeLocalX, eyeLocalY, eyeLocalZ, delta);
        double toX = Mth.lerp(NUDGE, localToX, localEyeX);
        double toY = Mth.lerp(NUDGE, localToY, localEyeY);
        double toZ = Mth.lerp(NUDGE, localToZ, localEyeZ);
        double fromX = Mth.lerp(NUDGE, localEyeX, localToX);
        double fromY = Mth.lerp(NUDGE, localEyeY, localToY);
        double fromZ = Mth.lerp(NUDGE, localEyeZ, localToZ);
        // 游标落在「起点段」的段内坐标上，越出 0 到 15 时由 Cursor 换段。
        int currentX = (int) Mth.lfloor(fromX);
        int currentY = (int) Mth.lfloor(fromY);
        int currentZ = (int) Mth.lfloor(fromZ);
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
     * 段内坐标：方块坐标减段原点，再加那一格的小数部分。{@code block} 必须是方块坐标，不是帧锚点。
     */
    private static double localIn(Int1024 sectionOrigin, Int1024 block, double local) {
        return local - sectionOrigin.subtract(block).toDouble();
    }

    /**
     * 逐格取形状的上下文：缓存当前段、它的 getter 与段内起点终点，跨段时才重建。
     *
     * <p>
     * 三个 {@code eyeBlock} 是眼睛所在方块的坐标，即帧锚点加整数步长，不是帧锚点本身；小数部分另外传。两个量必须
     * 同源取自同一轴，漏掉步长会让段内起点整体平移，表现为判交永远落空、选择框画不出来。
     */
    private static final class Cursor {

        private final Simulation simulation;
        private final Int1024 baseSectionX;
        private final Int1024 baseSectionY;
        private final Int1024 baseSectionZ;
        private final Int1024 eyeBlockX;
        private final Int1024 eyeBlockY;
        private final Int1024 eyeBlockZ;
        private final double eyeLocalX;
        private final double eyeLocalY;
        private final double eyeLocalZ;
        private final Vec3 delta;
        private final BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();

        private SimulationSection section;
        private SectionGetter getter;
        private Int1024 sectionX;
        private Int1024 sectionY;
        private Int1024 sectionZ;
        private boolean hasSection;
        private Vec3 localFrom;
        private Vec3 localTo;

        Cursor(Simulation simulation, Int1024 baseSectionX, Int1024 baseSectionY, Int1024 baseSectionZ,
                Int1024 eyeBlockX, Int1024 eyeBlockY, Int1024 eyeBlockZ, double eyeLocalX, double eyeLocalY,
                double eyeLocalZ, Vec3 delta) {
            this.simulation = simulation;
            this.baseSectionX = baseSectionX;
            this.baseSectionY = baseSectionY;
            this.baseSectionZ = baseSectionZ;
            this.eyeBlockX = eyeBlockX;
            this.eyeBlockY = eyeBlockY;
            this.eyeBlockZ = eyeBlockZ;
            this.eyeLocalX = eyeLocalX;
            this.eyeLocalY = eyeLocalY;
            this.eyeLocalZ = eyeLocalZ;
            this.delta = delta;
        }

        /** 该格上的命中；格不属于任何模拟段、为空、形状为空或射线不穿过形状时返回 null。 */
        Hit at(int x, int y, int z) {
            int sx = Math.floorDiv(x, SimulationSection.SIZE);
            int sy = Math.floorDiv(y, SimulationSection.SIZE);
            int sz = Math.floorDiv(z, SimulationSection.SIZE);
            Int1024 keyX = this.baseSectionX.add(Int1024.of((long) sx));
            Int1024 keyY = this.baseSectionY.add(Int1024.of((long) sy));
            Int1024 keyZ = this.baseSectionZ.add(Int1024.of((long) sz));
            if (!this.hasSection || !keyX.equals(this.sectionX) || !keyY.equals(this.sectionY)
                    || !keyZ.equals(this.sectionZ)) {
                this.hasSection = true;
                this.sectionX = keyX;
                this.sectionY = keyY;
                this.sectionZ = keyZ;
                this.section = this.simulation.sectionOrNull(keyX, keyY, keyZ);
                this.getter = this.section == null ? null : new SectionGetter(this.section, this.simulation);
                this.localFrom = new Vec3(
                        localIn(keyX.shift(4), this.eyeBlockX, this.eyeLocalX),
                        localIn(keyY.shift(4), this.eyeBlockY, this.eyeLocalY),
                        localIn(keyZ.shift(4), this.eyeBlockZ, this.eyeLocalZ));
                this.localTo = new Vec3(this.localFrom.x + this.delta.x, this.localFrom.y + this.delta.y,
                        this.localFrom.z + this.delta.z);
            }
            if (this.section == null) {
                return null;
            }
            int localX = x - sx * SimulationSection.SIZE;
            int localY = y - sy * SimulationSection.SIZE;
            int localZ = z - sz * SimulationSection.SIZE;
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
            return new Hit(this.sectionX.shift(4), this.sectionY.shift(4), this.sectionZ.shift(4), localX,
                    localY, localZ, shape);
        }
    }
}
