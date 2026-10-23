package com.inf.farlands.terrain.system.common.overworld.TheBeneath;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 单值映射节点，逐字取自 26.1.2 的 {@code DensityFunctions.Mapped}。
 *
 * <p>复制的原因是 square 与 invert 两种节点在这套 API 里没有公开工厂：{@code DensityFunctions.map} 的
 * 第二个形参是 {@code DensityFunctions$Mapped$Type}，而那个枚举是包私有的，包外连类型名都写不出来。
 *
 * <p>值域按 vanilla 的 {@link #create} 推算，不是简单地变换两端：SQUARE 与 ABS 的下界被抬到 0；
 * INVERT 的输入跨零时整段值域取正负无穷，否则两端互换。这几个判据决定 {最小, 最大} 声明，而声明值只
 * 参与区间推导，不参与取值。
 *
 * <p>与 vanilla 的差异只有序列化：本副本的类型名不带 {@code StringRepresentable}，{@code codec()} 返回
 * unit，因为这个节点是运行时注入的。
 */
public record TheBeneathMapped(Type type, DensityFunction input, double minValue, double maxValue)
        implements TheBeneathPureTransformer {

    public enum Type {
        ABS,
        SQUARE,
        CUBE,
        HALF_NEGATIVE,
        QUARTER_NEGATIVE,
        INVERT,
        SQUEEZE
    }

    public static TheBeneathMapped create(Type type, DensityFunction input) {
        double minValue = input.minValue();
        double maxValue = input.maxValue();
        double minImage = transform(type, minValue);
        double maxImage = transform(type, maxValue);
        if (type == Type.INVERT) {
            return minValue < 0.0 && maxValue > 0.0
                    ? new TheBeneathMapped(type, input, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                    : new TheBeneathMapped(type, input, maxImage, minImage);
        }
        return type != Type.ABS && type != Type.SQUARE
                ? new TheBeneathMapped(type, input, minImage, maxImage)
                : new TheBeneathMapped(type, input, Math.max(0.0, minValue), Math.max(minImage, maxImage));
    }

    private static double transform(Type type, double input) {
        return switch (type) {
            case ABS -> Math.abs(input);
            case SQUARE -> input * input;
            case CUBE -> input * input * input;
            case HALF_NEGATIVE -> input > 0.0 ? input : input * 0.5;
            case QUARTER_NEGATIVE -> input > 0.0 ? input : input * 0.25;
            case INVERT -> 1.0 / input;
            case SQUEEZE -> {
                double c = Mth.clamp(input, -1.0, 1.0);
                yield c / 2.0 - c * c * c / 24.0;
            }
        };
    }

    /** 平方。本链的 a 与 b 各用一次。 */
    public static DensityFunction square(DensityFunction input) {
        return create(Type.SQUARE, input);
    }

    /** 取倒数。本链只对常量 -0.09 用，值域按跨零规则退成两端互换。 */
    public static DensityFunction invert(DensityFunction input) {
        return create(Type.INVERT, input);
    }

    @Override
    public double transform(double input) {
        return transform(this.type, input);
    }

    /**
     * 只下钻输入，不把自己交给访问者。与 vanilla 同名方法逐字一致，那一处也不通知访问者：本节点不是
     * 标记，没有需要替换的缓存层。
     */
    @Override
    public TheBeneathMapped mapAll(DensityFunction.Visitor visitor) {
        return create(this.type, this.input.mapAll(visitor));
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
