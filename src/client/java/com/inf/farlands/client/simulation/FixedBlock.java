package com.inf.farlands.client.simulation;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 纯客户端的固定方块：坐标、包围盒与碰撞形状，供渲染与碰撞两处共用。
 *
 * <p>
 * 坐标三个分量都是 2147483650，超出 int 上界 3 格。渲染侧把它换算成 {@code 目标 - 相机} 的 double
 * 差，碰撞侧直接用这里的 {@link #BOX}，两处都不经过 {@code BlockPos} 与 {@code SectionPos}。
 *
 * <p>
 * 方块只活在客户端：服务端不认它，可玩界之外的移动由服务端无条件放行。
 */
public final class FixedBlock {

    /** 目标坐标，三轴同值。 */
    public static final double POS = 2147483650.0;

    /** 一格立方，与渲染出来的那块草方块重合。 */
    public static final AABB BOX = new AABB(POS, POS, POS, POS + 1.0, POS + 1.0, POS + 1.0);

    /** {@link #BOX} 的碰撞形状。 */
    public static final VoxelShape SHAPE = Shapes.create(BOX);

    private FixedBlock() {
    }
}
