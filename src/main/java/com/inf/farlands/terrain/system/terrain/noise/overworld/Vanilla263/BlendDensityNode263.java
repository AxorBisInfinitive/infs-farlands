package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * blending 密度节点，对应 26.3 的 {@code BlendDensityFunction}。
 *
 * <p>blender 为空时原样返回，这是常态，也是逐位一致的那一支。非空时走 26.1.2 的
 * {@code Blender.blendDensity}，它收 double 返 double，所以 blending 生效的那一片只能是近似一致。
 * 26.1.2 没有逐点 alpha 与 offset 的取数口，这一层补不回来。
 */
final class BlendDensityNode263 extends FloatNode263 {

    private final FloatNode263 input;

    BlendDensityNode263(FloatNode263 input) {
        this.input = input;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        float value = this.input.computeF(context);
        Blender blender = context.getBlender();
        if (blender == null || blender.isEmpty()) {
            return value;
        }
        return (float) blender.blendDensity(context, value);
    }
}
