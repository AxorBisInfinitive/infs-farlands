package com.inf.farlands.simulation.int64.noise;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 含水层，与 vanilla 的 {@code Aquifer} 同形。
 *
 * <p>{@link #computeSubstance} 返回 null 表示「不是流体，交给默认方块」，这与 vanilla 的材质规则同义；
 * 空空气由本方法自己返回，不由调用方按密度符号判。
 */
public interface Aquifer {

    BlockState computeSubstance(long x, long y, long z, double density);

    boolean shouldScheduleFluidUpdate();
}
