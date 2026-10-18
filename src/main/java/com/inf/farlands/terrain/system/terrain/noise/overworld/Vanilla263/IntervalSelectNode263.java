package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 阈值选择节点，对应 26.3 的 {@code IntervalSelectFunction}。
 *
 * <p>阈值表升序，取第一个使 {@code input < threshold} 的下标，全都越不过就取最后一个函数。
 * 判据是严格小于，写成小于等于会让每一个阈值格子都偏一档。
 */
final class IntervalSelectNode263 extends FloatNode263 {

    private final FloatNode263 input;
    private final float[] thresholds;
    private final FloatNode263[] functions;

    IntervalSelectNode263(FloatNode263 input, float[] thresholds, FloatNode263[] functions) {
        if (thresholds.length != functions.length - 1) {
            throw new IllegalArgumentException(
                    "farlands: interval select needs " + (functions.length - 1) + " thresholds, got "
                            + thresholds.length);
        }
        this.input = input;
        this.thresholds = thresholds;
        this.functions = functions;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        float value = this.input.computeF(context);
        for (int i = 0; i < this.thresholds.length; i++) {
            if (value < this.thresholds[i]) {
                return this.functions[i].computeF(context);
            }
        }
        return this.functions[this.functions.length - 1].computeF(context);
    }
}
