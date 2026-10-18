package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 插值节点，对应 26.3 的 {@code LerpFunction}。
 *
 * <p>权重恰为 0 或 1 时直接返回那一侧，不走插值。这一个特判决定了 26.3 在两端不产生插值误差，
 * 而化简成一句 lerp 会在端点附近偏一位。
 */
final class LerpNode263 extends FloatNode263 {

    private final FloatNode263 alpha;
    private final FloatNode263 first;
    private final FloatNode263 second;

    LerpNode263(FloatNode263 alpha, FloatNode263 first, FloatNode263 second) {
        this.alpha = alpha;
        this.first = first;
        this.second = second;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        float alphaValue = this.alpha.computeF(context);
        if (alphaValue == 0.0F) {
            return this.first.computeF(context);
        }
        if (alphaValue == 1.0F) {
            return this.second.computeF(context);
        }
        return Mth.lerp(alphaValue, this.first.computeF(context), this.second.computeF(context));
    }
}
