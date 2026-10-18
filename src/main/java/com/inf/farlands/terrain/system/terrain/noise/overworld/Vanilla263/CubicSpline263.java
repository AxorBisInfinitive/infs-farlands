package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.floats.Float2FloatFunction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 26.3 的 {@code CubicSpline} float 版。
 *
 * <p>26.1.2 也有同名的样条，但它的求值在 double 上。地形形状整条都由样条决定，所以这一份必须自己写，
 * 不能借用。
 *
 * <p>求值分三种情形：落在第一个定位点左侧、落在最后一个右侧、以及区间内。两侧走线性外推，区间内走
 * 三次 Hermite。三者的分支顺序与 26.3 一致，换序会在定位点附近给出不同结果。
 */
abstract class CubicSpline263 {

    abstract float sample(DensityFunction.FunctionContext context);

    static CubicSpline263 constant(float value) {
        return new Constant263(value);
    }

    static Builder263 builder(FloatNode263 coordinate) {
        return new Builder263(coordinate, Float2FloatFunction.identity());
    }

    /**
     * 带值变换的构造器。
     *
     * <p>变换只作用于字面量取值，嵌套样条不受影响。放大化预设靠它把海岸线与地形一起拉开。
     */
    static Builder263 builder(FloatNode263 coordinate, Float2FloatFunction valueTransformer) {
        return new Builder263(coordinate, valueTransformer);
    }

    /** 常量样条。 */
    private static final class Constant263 extends CubicSpline263 {

        private final float value;

        private Constant263(float value) {
            this.value = value;
        }

        @Override
        float sample(DensityFunction.FunctionContext context) {
            return this.value;
        }
    }

    /** 多点样条。 */
    private static final class Multipoint263 extends CubicSpline263 {

        private final FloatNode263 coordinate;
        private final float[] locations;
        private final CubicSpline263[] values;
        private final float[] derivatives;

        private Multipoint263(FloatNode263 coordinate, float[] locations, CubicSpline263[] values,
                float[] derivatives) {
            this.coordinate = coordinate;
            this.locations = locations;
            this.values = values;
            this.derivatives = derivatives;
        }

        @Override
        float sample(DensityFunction.FunctionContext context) {
            float input = this.coordinate.computeF(context);
            int start = Mth.binarySearch(0, this.locations.length, i -> input < this.locations[i]) - 1;
            int last = this.locations.length - 1;
            if (start < 0) {
                return linearExtend(input, this.values[0].sample(context), this.derivatives[0],
                        this.locations[0]);
            }
            if (start == last) {
                return linearExtend(input, this.values[last].sample(context), this.derivatives[last],
                        this.locations[last]);
            }
            float x1 = this.locations[start];
            float x2 = this.locations[start + 1];
            float t = (input - x1) / (x2 - x1);
            float y1 = this.values[start].sample(context);
            float y2 = this.values[start + 1].sample(context);
            float d1 = this.derivatives[start];
            float d2 = this.derivatives[start + 1];
            float a = d1 * (x2 - x1) - (y2 - y1);
            float b = -d2 * (x2 - x1) + (y2 - y1);
            return Mth.lerp(t, y1, y2) + t * (1.0F - t) * Mth.lerp(t, a, b);
        }

        /** 定位点之外的延拓。导数为零时是常量延拓，不接直线。 */
        private static float linearExtend(float input, float value, float derivative, float location) {
            return derivative == 0.0F ? value : value + derivative * (input - location);
        }
    }

    /** 构造器，定位点必须严格升序。 */
    static final class Builder263 {

        private final FloatNode263 coordinate;
        private final Float2FloatFunction valueTransformer;
        private final List<Float> locations = new ArrayList<>();
        private final List<CubicSpline263> values = new ArrayList<>();
        private final List<Float> derivatives = new ArrayList<>();

        private Builder263(FloatNode263 coordinate, Float2FloatFunction valueTransformer) {
            this.coordinate = coordinate;
            this.valueTransformer = valueTransformer;
        }

        Builder263 addPoint(float location, float value) {
            return this.addPoint(location, constant(this.valueTransformer.apply(value)), 0.0F);
        }

        Builder263 addPoint(float location, float value, float derivative) {
            return this.addPoint(location, constant(this.valueTransformer.apply(value)), derivative);
        }

        Builder263 addPoint(float location, CubicSpline263 spline) {
            return this.addPoint(location, spline, 0.0F);
        }

        Builder263 addPoint(float location, CubicSpline263 spline, float derivative) {
            if (!this.locations.isEmpty() && location <= this.locations.get(this.locations.size() - 1)) {
                throw new IllegalArgumentException("farlands: spline points must ascend, got " + location);
            }
            this.locations.add(location);
            this.values.add(spline);
            this.derivatives.add(derivative);
            return this;
        }

        CubicSpline263 build() {
            if (this.locations.isEmpty()) {
                throw new IllegalStateException("farlands: spline without points");
            }
            float[] locationArray = new float[this.locations.size()];
            float[] derivativeArray = new float[this.locations.size()];
            for (int i = 0; i < locationArray.length; i++) {
                locationArray[i] = this.locations.get(i);
                derivativeArray[i] = this.derivatives.get(i);
            }
            return new Multipoint263(this.coordinate, locationArray, this.values.toArray(new CubicSpline263[0]),
                    derivativeArray);
        }
    }
}
