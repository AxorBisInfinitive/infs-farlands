package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 3D 基础噪声节点，对应 26.3 的 {@code old_blended_noise}。
 *
 * <p>26.3 的 {@code BlendedNoise} 本身就是一个 {@code DensityFunction}，走采样器组合。这里把组合展开在
 * {@link BlendedNoise263#sample} 里，节点只负责把方块坐标传下去。
 */
final class BlendedNoiseNode263 extends FloatNode263 {

    private final BlendedNoise263 noise;

    BlendedNoiseNode263(BlendedNoise263 noise) {
        this.noise = noise;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        return this.noise.sample(context.blockX(), context.blockY(), context.blockZ());
    }
}
