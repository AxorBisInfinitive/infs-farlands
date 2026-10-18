package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/** 常量节点，对应 26.3 的 {@code ConstantFunction}。 */
final class ConstantNode263 extends FloatNode263 {

    private final float value;

    ConstantNode263(float value) {
        this.value = value;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        return this.value;
    }

    @Override
    public double minValue() {
        return this.value;
    }

    @Override
    public double maxValue() {
        return this.value;
    }
}
