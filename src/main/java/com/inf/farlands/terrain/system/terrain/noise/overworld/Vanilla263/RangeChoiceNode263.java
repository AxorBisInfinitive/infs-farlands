package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 区间选择节点，对应 26.3 的 {@code RangeChoiceFunction}。
 *
 * <p>区间是左闭右开，判据写成 {@code input >= min && input < max}。写成闭区间会在 max 那一格取错分支，
 * 而那一格正好是表面密度阈值所在的位置。
 */
final class RangeChoiceNode263 extends FloatNode263 {

    private final FloatNode263 input;
    private final float minInclusive;
    private final float maxExclusive;
    private final FloatNode263 whenInRange;
    private final FloatNode263 whenOutOfRange;

    RangeChoiceNode263(FloatNode263 input, float minInclusive, float maxExclusive, FloatNode263 whenInRange,
            FloatNode263 whenOutOfRange) {
        this.input = input;
        this.minInclusive = minInclusive;
        this.maxExclusive = maxExclusive;
        this.whenInRange = whenInRange;
        this.whenOutOfRange = whenOutOfRange;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        float value = this.input.computeF(context);
        return value >= this.minInclusive && value < this.maxExclusive
                ? this.whenInRange.computeF(context)
                : this.whenOutOfRange.computeF(context);
    }
}
