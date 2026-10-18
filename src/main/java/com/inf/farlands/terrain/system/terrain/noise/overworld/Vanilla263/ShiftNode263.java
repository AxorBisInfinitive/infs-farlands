package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 位移噪声节点，对应 26.3 的 {@code ShiftNoiseFunction} 三种形态。
 *
 * <p>三种形态都是 {@code noise.get(...) * 4.0F}，坐标先各乘 0.25。{@code ShiftB} 把 x 与 z 互换
 * 且 y 取 0，这一处换错方向会让气候偏移整片镜像。
 */
final class ShiftNode263 extends FloatNode263 {

    enum Type {
        SHIFT,
        SHIFT_A,
        SHIFT_B
    }

    private static final double COORDINATE_FACTOR = 0.25;
    private static final float VALUE_FACTOR = 4.0F;

    private final Type type;
    private final NoiseStack263 noise;

    ShiftNode263(Type type, NoiseStack263 noise) {
        this.type = type;
        this.noise = noise;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        double x = context.blockX() * COORDINATE_FACTOR;
        double y = context.blockY() * COORDINATE_FACTOR;
        double z = context.blockZ() * COORDINATE_FACTOR;
        switch (this.type) {
            case SHIFT:
                return this.noise.get(x, y, z) * VALUE_FACTOR;
            case SHIFT_A:
                return this.noise.get(x, 0.0, z) * VALUE_FACTOR;
            case SHIFT_B:
                return this.noise.get(z, x, 0.0) * VALUE_FACTOR;
            default:
                throw new IllegalStateException("farlands: unknown shift type " + this.type);
        }
    }
}
