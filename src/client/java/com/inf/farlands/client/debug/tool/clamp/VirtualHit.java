package com.inf.farlands.client.debug.tool.clamp;

import com.inf.farlands.util.pos.ExactPosition;
import love.crysh.long2.ints.Int1024;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 钳制模式当前这一刻的虚拟面命中，两条通道。
 *
 * <p>
 * 可表示通道是 {@link BlockPos}：格号在可玩方块界内时由 {@code EntityPickMixin} 写，vanilla 的空气检查与形状两个
 * 重定向据此放行并给出整格形状。
 *
 * <p>
 * 精确通道是 {@link Surface}：格号在可玩界外时由 {@code LevelRendererMixin} 在抽取阶段写，选择框由同一处按相机相对
 * 量自绘。模拟层的选择框读 {@link #exactActive()} 让位，两条选择框因此在界外互斥。
 *
 * <p>
 * 两条通道互斥，同一个格子不会同时出现在两边。
 */
public final class VirtualHit {

    /** 虚拟面的精确命中：面下那一格的三轴格号，加落点的世界量投影。 */
    public record Surface(Int1024 x, Int1024 y, Int1024 z, Vec3 location) {
    }

    private static BlockPos current;

    private static Surface exact;

    private VirtualHit() {
    }

    public static void set(BlockPos pos) {
        current = pos;
    }

    public static void clear() {
        current = null;
    }

    /** 当前命中 HitResult 是否为虚拟面命中。 */
    public static boolean isCurrent(HitResult hit) {
        return current != null && hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(current);
    }

    public static boolean isPos(BlockPos pos) {
        return current != null && current.equals(pos);
    }

    public static void setExact(Surface surface) {
        exact = surface;
    }

    public static void clearExact() {
        exact = null;
    }

    /** 本帧的虚拟面命中是否落在可玩方块界外，即精确通道有没有值。 */
    public static boolean exactActive() {
        return exact != null;
    }

    /** 本帧的精确命中，没有则 null。 */
    public static Surface exact() {
        return exact;
    }

    /**
     * 视线穿过当前段底平面时，面下那一格的精确格号。
     *
     * <p>
     * 算式只有这一份：交互门与选择框两处都调它，只有射程上限不同。落点算在实体自己的帧里，锚点承担量级、局部量承担
     * 精度，所以从 {@code 2^31} 到 {@code 2^1023} 走同一条算式。
     *
     * @param maxDistance 射程上限，主 pick 传两个交互距离的较大者
     * @return 命中则返回格号与落点，视线朝上、平面在眼后或超出射程时返回 null
     */
    public static Surface surface(Entity entity, float partialTicks, double maxDistance) {
        if (!(entity instanceof ExactPosition exactPosition)) {
            return null;
        }
        Int1024 anchorX = exactPosition.farlands$anchor(ExactPosition.POS_X);
        Int1024 anchorY = exactPosition.farlands$anchor(ExactPosition.POS_Y);
        Int1024 anchorZ = exactPosition.farlands$anchor(ExactPosition.POS_Z);
        double localX = exactPosition.farlands$lerpLocal(ExactPosition.POS_X, partialTicks);
        double localY = exactPosition.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks)
                + (double) entity.getEyeHeight();
        double localZ = exactPosition.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks);
        Int1024 sectionBottom = ExactPosition.sectionBottom(exactPosition, ExactPosition.POS_Y);
        double floorLocalY = sectionBottom.subtract(anchorY).toDouble();
        Vec3 direction = entity.getViewVector(partialTicks);
        if (direction.y >= 0.0 || localY <= floorLocalY) {
            return null;
        }
        double t = (floorLocalY - localY) / direction.y;
        if (t < 0.0 || t > maxDistance) {
            return null;
        }
        double hitX = localX + direction.x * t;
        double hitZ = localZ + direction.z * t;
        Int1024 cellX = anchorX.add(Int1024.of((long) Math.floor(hitX)));
        Int1024 cellY = sectionBottom.subtract(Int1024.ONE);
        Int1024 cellZ = anchorZ.add(Int1024.of((long) Math.floor(hitZ)));
        return new Surface(cellX, cellY, cellZ,
                new Vec3(anchorX.toDouble() + hitX, sectionBottom.toDouble(), anchorZ.toDouble() + hitZ));
    }
}
