package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * 26.3 的 {@code preliminary_surface_level}，逐项对齐 {@code NoiseRouterData.preliminarySurfaceLevel}。
 *
 * <p>
 * 它由本系统的 offset 与 factor 拼成，所以含水层的水线、地表规则的 {@code above_preliminary_surface}、
 * 深暗判定三处读到的都是 26.3 自己的表面，不再是宿主 level 的。
 *
 * <p>
 * 算式与 26.3 原式逐项相同，只有三处写法差异：{@code div(constant, factor)} 写成
 * {@code mul(constant, factor.invert())}，{@code sub(x, offset)} 写成 {@code add(x, mul(-1, offset))}，
 * 以及 {@code remap(x, 1.5, -1.5, -64, 320)} 展开成 {@code -128x + 128}。那三个工厂在 26.1.2 里没有公开入口。
 *
 * <p>
 * 定义域是 26.3 的口径：下界 -64、上界由 {@code upperBound} 夹在 [-40, 320]，扫描步长 8。所以返回值恒落在
 * [-64, 320]，不触 int 端点。
 */
final class PreliminarySurface263 implements DensityFunction.SimpleFunction {

    /** 26.3 的扫描步长，固定 8，与维度设置的竖直 cell 无关。 */
    private static final int CELL_HEIGHT = 8;

    /** 26.3 的 {@code remap(x, 1.5, -1.5, -64, 320)} 展开后的两个常数。 */
    private static final double UPPER_BOUND_SCALE = -128.0;

    private static final double UPPER_BOUND_OFFSET = 128.0;

    private final DensityFunction surface;

    PreliminarySurface263(FloatNode263 offset, FloatNode263 factor) {
        DensityFunction upperBound = DensityFunctions.add(
                DensityFunctions.mul(
                        DensityFunctions.add(
                                DensityFunctions.mul(DensityFunctions.constant(0.2734375), factor.invert()),
                                DensityFunctions.mul(DensityFunctions.constant(-1.0), offset)),
                        DensityFunctions.constant(UPPER_BOUND_SCALE)),
                DensityFunctions.constant(UPPER_BOUND_OFFSET)).clamp(-40.0, 320.0);
        // noiseGradientDensity(factor, offsetToDepth(offset))
        DensityFunction depth = DensityFunctions.add(
                DensityFunctions.yClampedGradient(-64, 320, 1.5, -1.5), offset);
        DensityFunction gradient = DensityFunctions.mul(DensityFunctions.constant(4.0),
                DensityFunctions.mul(depth, factor).quarterNegative());
        DensityFunction base = DensityFunctions.add(gradient, DensityFunctions.constant(-0.703125))
                .clamp(-64.0, 64.0);
        // slideOverworld：先顶段 240 到 256 降到 -0.078125，再底段 -64 到 -40 升到 0.1171875。
        DensityFunction slid = DensityFunctions.lerp(
                DensityFunctions.yClampedGradient(240, 256, 1.0, 0.0), -0.078125, base);
        slid = DensityFunctions.lerp(DensityFunctions.yClampedGradient(-64, -40, 0.0, 1.0),
                0.1171875, slid);
        this.surface = DensityFunctions.findTopSurface(
                DensityFunctions.add(slid, DensityFunctions.constant(-0.390625)), upperBound, -64,
                CELL_HEIGHT);
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return this.surface.compute(context);
    }

    @Override
    public double minValue() {
        return -64.0;
    }

    @Override
    public double maxValue() {
        return 320.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
