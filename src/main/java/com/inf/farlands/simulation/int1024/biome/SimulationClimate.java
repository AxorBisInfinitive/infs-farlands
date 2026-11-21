package com.inf.farlands.simulation.int1024.biome;

import com.inf.farlands.simulation.int1024.noise.Router;
import love.crysh.long2.ints.Int1024;

import net.minecraft.world.level.biome.Climate;

/**
 * 1024 位 quart 上的气候采样，产出 vanilla 的 {@link Climate.TargetPoint}。
 *
 * <p>
 * 只承担 {@code Climate.Sampler.sample} 的前半段。那个方法的入参与它内部的点上下文都是 int，坐标一换成
 * {@link Int1024} 就接不住，所以这一段必须自己写。它之后的量化、{@code TargetPoint} 与参数表查找都与坐标宽度
 * 无关，直接用 vanilla 的类型，不重写。
 *
 * <p>
 * 六条密度的位置对应照 {@code RandomState} 构造 {@code Climate.Sampler} 的那一处：router 的 temperature、
 * vegetation、continents、erosion、depth、ridges 依次填 sampler 的 temperature、humidity、continentalness、
 * erosion、depth、weirdness。顺序错了值就落到别的气候轴上，编译期看不出来。
 *
 * <p>
 * 纯函数：不读注册表、不持状态，六个入参决定六个出参。
 */
public final class SimulationClimate {

    private SimulationClimate() {
    }

    /** 该 quart 的目标气候点。块坐标是 quart 左移两位，与 {@code QuartPos.toBlock} 同式。 */
    public static Climate.TargetPoint target(Int1024 quartX, Int1024 quartY, Int1024 quartZ, Router router) {
        Int1024 blockX = quartX.shift(2);
        Int1024 blockY = quartY.shift(2);
        Int1024 blockZ = quartZ.shift(2);
        return Climate.target(
                (float) router.temperature().compute(blockX, blockY, blockZ),
                (float) router.vegetation().compute(blockX, blockY, blockZ),
                (float) router.continents().compute(blockX, blockY, blockZ),
                (float) router.erosion().compute(blockX, blockY, blockZ),
                (float) router.depth().compute(blockX, blockY, blockZ),
                (float) router.ridges().compute(blockX, blockY, blockZ));
    }
}
