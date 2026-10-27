package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 26.3 的矿脉三条函数，喂给 26.1.2 的 {@code OreVeinifier}。
 *
 * <p>
 * 26.3 把矿脉写成 material rule，即 {@code OreVeinRule}，判定面与 26.1.2 的 {@code OreVeinifier} 逐项等价：
 * 类型由 toggle 的符号分，铜带 {@code y ∈ [0,50]}、铁带 {@code y ∈ [-60,-8]}，带边 20 格渐弱 -0.2，
 * 阈值 {@code |t| + r}、掩码 {@code max(|A|,|B|) < 0.08}、固体度 0.7、富集度由 {@code 0.4..0.6} 映射到
 * {@code 0.1..0.3}、缺口 {@code noise > -0.3}、原矿 0.02，全部同值。所以这里只造三条函数，判定体不动。
 *
 * <p>
 * 与 26.1.2 原式有三处必须照 26.3 而不能照抄 26.1.2：窗口按 cell 高对齐成 {@code [-64, 56]}、
 * A 与 B 的越界值取 1 而 toggle 取 0、插值 cell 写死 4 与 8 而不跟维度设置。前两处决定带边 4 格内的形状，
 * 第三处保证换到别的维度时粒度不变。
 *
 * <p>
 * 唯一数值差在等号那一格：26.1.2 保留 {@code |t| + r >= 0.4}，26.3 要求 {@code > 0.4}。
 *
 * <p>
 * 顺序差保留现状：26.3 的矿脉排在 material rule 序列里地表规则之前，本 port 的 {@code OreVeinifier} 在填充期
 * 判块、地表随后覆盖，所以靠近地表那一带可能被地表改回去。
 *
 * <p>
 * 随机源不动：{@code NoiseChunk} 用 level 的 {@code oreRandom()}，26.3 用 level 的
 * {@code RandomState}，两者同源，与系统 seed 无关。
 */
record OreVeins263(DensityFunction toggle, DensityFunction ridged, DensityFunction gap) {

    /** 26.3 的窗口按 cell 高对齐：铁带下界 -60 对齐到 -64，铜带上界 50 对齐到 56。 */
    private static final int WINDOW_MIN_Y = -64;

    private static final int WINDOW_MAX_INCLUSIVE_Y = 56;

    /** 26.3 给这两条节点写死的插值 cell。 */
    private static final int CELL_WIDTH = 4;

    private static final int CELL_HEIGHT = 8;

    /** 造三条函数。{@code y} 是 26.3 的 {@code Y}，竖直门用它。 */
    static OreVeins263 of(FloatNode263 y, PositionalRandomFactory noises, boolean enableFarlands) {
        FloatNode263 toggle = interpolated(inWindow(y,
                NoiseNode263.plain(NoiseData263.ORE_VEININESS.create(noises, enableFarlands), 1.5, 1.5),
                0.0F));
        FloatNode263 veinA = abs(interpolated(inWindow(y,
                NoiseNode263.plain(NoiseData263.ORE_VEIN_A.create(noises, enableFarlands), 4.0, 4.0),
                1.0F)));
        FloatNode263 veinB = abs(interpolated(inWindow(y,
                NoiseNode263.plain(NoiseData263.ORE_VEIN_B.create(noises, enableFarlands), 4.0, 4.0),
                1.0F)));
        FloatNode263 ridged = new BinaryNode263(BinaryNode263.Type.ADD, new ConstantNode263(-0.08F),
                new BinaryNode263(BinaryNode263.Type.MAX, veinA, veinB));
        FloatNode263 gap = NoiseNode263.plain(NoiseData263.ORE_GAP.create(noises, enableFarlands), 1.0, 1.0);
        return new OreVeins263(toggle, ridged, gap);
    }

    /** 26.3 的 {@code yLimitedInterpolatable}：区间左闭右开，所以上界要加一。 */
    private static FloatNode263 inWindow(FloatNode263 y, FloatNode263 whenInRange, float whenOutOfRange) {
        return new RangeChoiceNode263(y, WINDOW_MIN_Y, WINDOW_MAX_INCLUSIVE_Y + 1, whenInRange,
                new ConstantNode263(whenOutOfRange));
    }

    private static FloatNode263 interpolated(FloatNode263 node) {
        return new InterpolatedNode263(node, CELL_WIDTH, CELL_HEIGHT, false);
    }

    private static FloatNode263 abs(FloatNode263 node) {
        return new UnaryNode263(UnaryNode263.Type.ABS, node);
    }
}
