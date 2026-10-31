package com.inf.farlands.util.pos;

import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 帧内的方块碰撞源，由客户端模拟层实现，主源集的 {@code collectColliders} 按需调用。
 *
 * <p>
 * 存在的理由是坐标空间：帧的锚点非零时，物理的查询盒是局部盒，而 vanilla 的
 * {@code getBlockCollisions} 按世界坐标枚举方块，两者对不上。把模拟层那套逐格判交提到一个方法里，主源集就能在
 * 帧内直接取形，不必让 vanilla 那条路径看一个塌陷的盒。
 *
 * <p>
 * 服务端没有模拟层，{@code level} 不是本接口的实现者，调用方退回 vanilla 路径。
 */
public interface ExactCollisionSource {

    /** 该查询盒与模拟块的碰撞形状，坐标与该盒同帧。 */
    List<VoxelShape> farlands$frameCollisions(Entity source, AABB box);
}
