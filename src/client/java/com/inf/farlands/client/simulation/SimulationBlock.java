package com.inf.farlands.client.simulation;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 一个模拟方块：方块状态，加两个可选的形状覆盖。
 *
 * <p>
 * 形状为 {@code null} 表示按状态派生，碰撞走 {@code state.getCollisionShape}，拾取走 {@code state.getShape}，
 * 也就是 vanilla 的那两个入口。非 {@code null} 时按用途取用：{@code collisionShape} 只服务碰撞，
 * {@code outlineShape} 只服务拾取与选择框；几何一律由状态的模型决定，与形状无关。
 *
 * <p>
 * 覆盖形状必须非空且落在方块格内，六轴端点都在 {@code [0, 1]}。格内是本条的不变式：三处段级粗筛都按段立方做，
 * 只有当形状是格的子集时它才是超集。空形状用 {@code null} 表达，即按状态派生，不要传空形状。
 */
public record SimulationBlock(BlockState state, VoxelShape collisionShape, VoxelShape outlineShape) {

    public SimulationBlock {
        if (state == null) {
            throw new IllegalArgumentException("farlands: simulation block needs a block state");
        }
        farlands$checkShape(collisionShape);
        farlands$checkShape(outlineShape);
    }

    /** 覆盖形状的两条校验：非空，且六轴端点都在 {@code [0, 1]}。 */
    private static void farlands$checkShape(VoxelShape shape) {
        if (shape == null) {
            return;
        }
        if (shape.isEmpty()) {
            throw new IllegalArgumentException("farlands: simulation shape must not be empty, use null to derive");
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            double min = shape.min(axis);
            double max = shape.max(axis);
            if (min < 0.0 || max > 1.0) {
                throw new IllegalArgumentException(
                        "farlands: simulation shape must stay in the block cell, got " + min + " .. " + max);
            }
        }
    }

    /** 形状全部按状态派生。 */
    public static SimulationBlock of(BlockState state) {
        return new SimulationBlock(state, null, null);
    }

    /** 整格：碰撞与轮廓都取满格，不看状态。 */
    public static SimulationBlock full(BlockState state) {
        return new SimulationBlock(state, Shapes.block(), Shapes.block());
    }
}
