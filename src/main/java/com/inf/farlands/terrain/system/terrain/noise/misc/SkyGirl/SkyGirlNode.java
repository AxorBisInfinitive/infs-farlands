package com.inf.farlands.terrain.system.terrain.noise.misc.SkyGirl;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 只在网格点上给出正密度，其余位置给 {@code NaN}。
 *
 * <p>判据落在 26.1.2 的方块判据上：{@code NaN > 0} 为假，于是判空气；格点上的正数判方块。所以这个节点
 * 不需要知道方块类型，也不需要知道高度范围。
 *
 * <p>格点处返回有限正数而不是无穷。无穷与任何量相减都会变成 {@code NaN}，一旦上游有加减，格点本身
 * 也会消失，网格就成了空世界。
 *
 * <p>非格点返回 {@code NaN} 而不是负密度。负密度会交给 aquifer 接手，可能填出流体而不是空气；
 * {@code NaN} 只在上面的判据上落空，不会走到那一步。
 */
final class SkyGirlNode implements DensityFunction.SimpleFunction {

    /** 格点处的密度。任意正数等价，取 1。 */
    private static final float SOLID = 1.0F;

    /** 水平间距，同时用于 X 与 Z。 */
    private final int cellXz;

    /** 竖直间距。 */
    private final int cellY;

    SkyGirlNode(int cellXz, int cellY) {
        this.cellXz = cellXz;
        this.cellY = cellY;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        boolean onGrid = Math.floorMod(context.blockX(), this.cellXz) == 0
                && Math.floorMod(context.blockY(), this.cellY) == 0
                && Math.floorMod(context.blockZ(), this.cellXz) == 0;
        return onGrid ? SOLID : Float.NaN;
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return SOLID;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
