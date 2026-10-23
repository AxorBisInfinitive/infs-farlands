package com.inf.farlands.terrain.system.common.overworld.TheBeneath;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 单输入纯变换节点，逐字取自 26.1.2 的 {@code DensityFunctions.PureTransformer}。
 *
 * <p>那个接口是 {@code DensityFunctions} 的 private 成员，包外引用不到，所以复制一份。它把 compute 与
 * fillArray 都收敛到 {@link #transform(double)} 上，下面 {@link TheBeneathClamp} 与
 * {@link TheBeneathMapped} 因此只写那一个方法。
 */
public interface TheBeneathPureTransformer extends DensityFunction {

    /** 被变换的输入节点。 */
    DensityFunction input();

    @Override
    default double compute(DensityFunction.FunctionContext context) {
        return this.transform(this.input().compute(context));
    }

    @Override
    default void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        this.input().fillArray(output, contextProvider);
        for (int i = 0; i < output.length; i++) {
            output[i] = this.transform(output[i]);
        }
    }

    /** 单值变换。 */
    double transform(double input);
}
