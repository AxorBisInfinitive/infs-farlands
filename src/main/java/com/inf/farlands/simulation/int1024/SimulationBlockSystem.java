package com.inf.farlands.simulation.int1024;

import love.crysh.long2.ints.Int1024;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 逐块几何地形系统，与 terrain 的 {@code BlockSystem} 同形。
 *
 * <p>
 * 填充器为它走直写路径：不建密度链、不取 {@code Settings}、不碰含水层。每格问一次，答案由填充器写进段容器。
 *
 * <p>
 * 契约三条。前两条违反会当场炸出来，第三条是直写路径的固有性质，补不回来：
 *
 * <ol>
 * <li>每格必答，返回 {@code AIR} 表示空气。返回 null 是契约违反，填充器用 {@code requireNonNull} 当场抛，不把它
 * 当空气。</li>
 * <li>只判定，不写段。写由填充器做，所以本接口没有段容器可碰。</li>
 * <li>不建方块实体，不触发邻接更新。需要支撑的方块会一直站着，直到附近格子被改动。</li>
 * </ol>
 *
 * <p>
 * 无状态且纯方法：实例随 level 走，被 {@code farlands-sim} 上的多个填充任务共享，图案只该依赖坐标。
 */
public interface SimulationBlockSystem extends SimulationTerrainSystem {

    /** 一格的方块。{@code AIR} 表示空气。不得返回 null。 */
    BlockState fillBlock(Int1024 x, Int1024 y, Int1024 z);
}
