package com.inf.farlands.simulation.int1024.noise.instance;

import com.inf.farlands.simulation.int1024.noise.Density;
import love.crysh.long2.ints.Int1024;

/**
 * 常值密度。vanilla 的 {@code DensityFunctions.Constant}。
 *
 * <p>作为加减乘的一侧时会被 {@link TwoArgument#create} 折成 {@link MulOrAdd}，与 vanilla 的工厂同式。
 */
public record Constant(double value) implements Density {

    @Override
    public double compute(Int1024 x, Int1024 y, Int1024 z) {
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

    @Override
    public Density mapAll(Visitor visitor) {
        return visitor.apply(this);
    }
}
