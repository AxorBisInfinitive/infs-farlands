package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 一元节点，对应 26.3 的 {@code UnaryFunction} 十一种类型。
 *
 * <p>全部算式逐条照抄。其中三处看起来可以化简但不能动：半数与四分之一负用的是
 * {@code x > 0 ? x : x * f} 而不是乘一个符号，squeeze 先钳后立方，对数接受负值得到 NaN 而不是先钳。
 */
final class UnaryNode263 extends FloatNode263 {

    enum Type {
        ABS,
        SQUARE,
        CUBE,
        SQRT,
        HALF_NEGATIVE,
        QUARTER_NEGATIVE,
        RECIPROCAL,
        NEGATE,
        SQUEEZE,
        LOG,
        SIGN
    }

    private final Type type;
    private final FloatNode263 input;

    UnaryNode263(Type type, FloatNode263 input) {
        this.type = type;
        this.input = input;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        float value = this.input.computeF(context);
        switch (this.type) {
            case ABS:
                return Math.abs(value);
            case SQUARE:
                return Mth.square(value);
            case CUBE:
                return Mth.cube(value);
            case SQRT:
                return Mth.sqrt(value);
            case HALF_NEGATIVE:
                return value > 0.0F ? value : value * 0.5F;
            case QUARTER_NEGATIVE:
                return value > 0.0F ? value : value * 0.25F;
            case RECIPROCAL:
                return 1.0F / value;
            case NEGATE:
                return -value;
            case SQUEEZE:
                return squeeze(value);
            case LOG:
                return (float) Math.log(value);
            case SIGN:
                return Math.signum(value);
            default:
                throw new IllegalStateException("farlands: unknown unary type " + this.type);
        }
    }

    private static float squeeze(float value) {
        float clamped = Mth.clamp(value, -1.0F, 1.0F);
        return clamped / 2.0F - Mth.cube(clamped) / 24.0F;
    }
}
