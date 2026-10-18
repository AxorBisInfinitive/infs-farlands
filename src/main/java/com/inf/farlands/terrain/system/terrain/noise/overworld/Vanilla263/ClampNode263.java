package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/** 钳制节点，对应 26.3 的 {@code ClampFunction}。 */
final class ClampNode263 extends FloatNode263 {

    private final FloatNode263 input;
    private final float min;
    private final float max;

    ClampNode263(FloatNode263 input, float min, float max) {
        this.input = input;
        this.min = min;
        this.max = max;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        return Mth.clamp(this.input.computeF(context), this.min, this.max);
    }
}
