package com.inf.farlands.terrain;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 逐块几何地形系统，fillBlock 每格直接判定，不参与密度链。
 *
 * <p>
 * 填充器为它走直写路径：不建 {@code NoiseChunk}，不装载密度链与插值器，逐段逐格写 section。本类因此没有
 * {@code createFinalDensity}，也不需要 aquifer 的替身。
 *
 * <p>
 * {@link #fillBlock} 必须对每一格都给出方块，返回 null 是契约违反。今天的行为不是「交回密度链」：直写路径
 * 不判密度，填充器在写入前用 {@code requireNonNull} 把违约当场炸出来，而不是把它当空气。
 */
public interface BlockSystem extends TerrainSystem {

    /** 块级判定，返回 AIR 表示空气，返回方块表示实。不得返回 null。 */
    BlockState fillBlock(int x, int y, int z);
}
