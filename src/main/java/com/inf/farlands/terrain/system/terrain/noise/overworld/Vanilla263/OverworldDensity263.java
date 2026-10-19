package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 26.3 主世界 {@code final_density} 的树构造，逐行对齐 {@code NoiseRouterData.registerTerrainNoises}。
 *
 * <p>与 26.3 有两处差异。其一，末尾不加 {@code beardifier}：26.1.2 的 {@code NoiseChunk} 会把
 * finalDensity 外包一层 {@code add(..., BeardifierMarker)}，求值时换成真正的 Beardifier，这里再加一遍
 * 就是双加，地形会整体抬高。其二，顶部渐消默认关闭，见 {@code slideOverworld}。
 *
 * <p>blending 的两个输入按无 blender 的默认值落成常量 1.0F 与 0.0F。26.1.2 没有逐点 alpha 与 offset
 * 的取数口，这一层补不回来，边界写在 {@link BlendDensityNode263}。
 */
final class OverworldDensity263 {

    /** 26.3 的 {@code DimensionType.MIN_Y * 2}。不能读运行期字段，本仓库把 DimensionType 改成了全 int 量程。 */
    private static final int Y_BELOW_BOTTOM = -4064;

    /** 26.3 的 {@code DimensionType.MAX_Y * 2}。 */
    private static final int Y_ABOVE_TOP = 4062;

    private OverworldDensity263() {
    }

    static FloatNode263 build(PositionalRandomFactory noises, boolean enableFarlands, boolean topFadeEnabled,
            boolean enableJagged) {
        FloatNode263 shiftX = cache(new ShiftNode263(ShiftNode263.Type.SHIFT_A,
                NoiseData263.SHIFT.create(noises, enableFarlands)));
        FloatNode263 shiftZ = cache(new ShiftNode263(ShiftNode263.Type.SHIFT_B,
                NoiseData263.SHIFT.create(noises, enableFarlands)));
        FloatNode263 y = GradientNode263.yClamped(Y_BELOW_BOTTOM, Y_ABOVE_TOP, Y_BELOW_BOTTOM, Y_ABOVE_TOP);

        FloatNode263 continents = cache(
                shiftedXz(NoiseData263.CONTINENTALNESS, noises, enableFarlands, shiftX, shiftZ));
        FloatNode263 erosion = cache(shiftedXz(NoiseData263.EROSION, noises, enableFarlands, shiftX, shiftZ));
        FloatNode263 weirdness = cache(shiftedXz(NoiseData263.RIDGE, noises, enableFarlands, shiftX, shiftZ));
        FloatNode263 ridgesFolded = peaksAndValleys(weirdness);

        FloatNode263 offset = blended(
                add(new ConstantNode263(-0.50375F),
                        new SplineNode263(
                                TerrainProvider263.overworldOffset(continents, erosion, ridgesFolded, false))),
                new ConstantNode263(0.0F));
        FloatNode263 factor = blended(
                new SplineNode263(
                        TerrainProvider263.overworldFactor(continents, erosion, weirdness, ridgesFolded, false)),
                new ConstantNode263(10.0F));
        FloatNode263 depth = add(GradientNode263.yClamped(-64, 320, 1.5F, -1.5F), offset);
        FloatNode263 initialDensity = noiseGradientDensity(factor,
                jagged(noises, enableFarlands, continents, erosion, weirdness, ridgesFolded, depth, enableJagged));
        FloatNode263 slopedCheese = cache(add(initialDensity, base3dNoise(noises, enableFarlands)));

        FloatNode263 spaghettiRoughness = spaghettiRoughnessFunction(noises, enableFarlands);
        FloatNode263 entrances = entrances(noises, enableFarlands, spaghettiRoughness);
        FloatNode263 spaghetti2D = spaghetti2D(noises, enableFarlands);
        FloatNode263 pillars = pillars(noises, enableFarlands);
        FloatNode263 underground = underground(noises, enableFarlands, slopedCheese, entrances, spaghetti2D,
                spaghettiRoughness, pillars);
        FloatNode263 caves = caves(slopedCheese, entrances, underground);
        FloatNode263 noodle = noodle(noises, enableFarlands, y);

        return min(postProcess(slideOverworld(caves, topFadeEnabled), 4, 8), noodle);
    }

    /**
     * 把锯齿度加到深度上。
     *
     * <p>关掉时返回传入的深度本身，不是加一个 0。左零短路已按缓冲语义去掉，{@code 0 × NaN} 会照常
     * 传播，用一个 0 顶替锯齿度等于把 NaN 请回来，比不关更糟。所以关掉只能是结构上不挂这一项。
     */
    private static FloatNode263 jagged(PositionalRandomFactory noises, boolean enableFarlands,
            FloatNode263 continents, FloatNode263 erosion, FloatNode263 weirdness, FloatNode263 ridgesFolded,
            FloatNode263 depth, boolean enableJagged) {
        if (!enableJagged) {
            return depth;
        }
        FloatNode263 jaggedNoise = NoiseNode263.plain(NoiseData263.JAGGED.create(noises, enableFarlands), 1500.0,
                0.0);
        FloatNode263 unscaledJaggedness = blended(
                new SplineNode263(TerrainProvider263.overworldJaggedness(continents, erosion, weirdness,
                        ridgesFolded, false)),
                new ConstantNode263(0.0F));
        return add(depth,
                cache(mul(unscaledJaggedness, unary(UnaryNode263.Type.HALF_NEGATIVE, jaggedNoise))));
    }

    private static FloatNode263 caves(FloatNode263 slopedCheese, FloatNode263 entrances,
            FloatNode263 underground) {
        FloatNode263 surfaceWithEntrances = min(slopedCheese, mul(entrances, new ConstantNode263(5.0F)));
        return new RangeChoiceNode263(slopedCheese, -1000000.0F, 1.5625F, surfaceWithEntrances, underground);
    }

    private static FloatNode263 underground(PositionalRandomFactory noises, boolean enableFarlands,
            FloatNode263 slopedCheese, FloatNode263 entrances, FloatNode263 spaghetti2D,
            FloatNode263 spaghettiRoughness, FloatNode263 pillars) {
        FloatNode263 layerNoise = NoiseNode263.plain(NoiseData263.CAVE_LAYER.create(noises, enableFarlands), 1.0,
                8.0);
        FloatNode263 layerizedCaverns = mul(unary(UnaryNode263.Type.SQUARE, layerNoise),
                new ConstantNode263(4.0F));
        FloatNode263 cheese = NoiseNode263.plain(NoiseData263.CAVE_CHEESE.create(noises, enableFarlands), 1.0,
                0.6666666666666666);
        FloatNode263 solidified = add(
                clamp(add(cheese, new ConstantNode263(0.27F)), -1.0F, 1.0F),
                clamp(add(mul(slopedCheese, new ConstantNode263(-0.64F)), new ConstantNode263(1.5F)), 0.0F,
                        0.5F));
        FloatNode263 baseCaveDensity = add(layerizedCaverns, solidified);
        FloatNode263 subtractions = min(min(baseCaveDensity, entrances),
                add(spaghetti2D, spaghettiRoughness));
        FloatNode263 pillarsCut = new RangeChoiceNode263(pillars, -1000000.0F, 0.03F,
                new ConstantNode263(-1000000.0F), pillars);
        return max(subtractions, pillarsCut);
    }

    private static FloatNode263 spaghettiRoughnessFunction(PositionalRandomFactory noises,
            boolean enableFarlands) {
        FloatNode263 roughness = unary(UnaryNode263.Type.ABS, NoiseNode263.plain(
                NoiseData263.SPAGHETTI_ROUGHNESS.create(noises, enableFarlands), 1.0, 1.0));
        FloatNode263 modulator = mappedNoise(NoiseData263.SPAGHETTI_ROUGHNESS_MODULATOR, noises, enableFarlands,
                1.0, 1.0, 0.0F, -0.1F);
        return cache(mul(modulator, add(roughness, new ConstantNode263(-0.4F))));
    }

    private static FloatNode263 entrances(PositionalRandomFactory noises, boolean enableFarlands,
            FloatNode263 spaghettiRoughness) {
        FloatNode263 rarity = cache(NoiseNode263.plain(
                NoiseData263.SPAGHETTI_3D_RARITY.create(noises, enableFarlands), 2.0, 1.0));
        FloatNode263 thickness = mappedNoise(NoiseData263.SPAGHETTI_3D_THICKNESS, noises, enableFarlands, 1.0,
                1.0, -0.065F, -0.088F);
        FloatNode263 cave1 = raritySampler(rarity, NoiseData263.SPAGHETTI_3D_1, noises, enableFarlands,
                new float[] { -0.5F, 0.0F, 0.5F }, new float[] { 0.75F, 1.0F, 1.5F, 2.0F });
        FloatNode263 cave2 = raritySampler(rarity, NoiseData263.SPAGHETTI_3D_2, noises, enableFarlands,
                new float[] { -0.5F, 0.0F, 0.5F }, new float[] { 0.75F, 1.0F, 1.5F, 2.0F });
        FloatNode263 spaghetti3D = clamp(add(max(cave1, cave2), thickness), -1.0F, 1.0F);
        FloatNode263 bigEntrance = NoiseNode263.plain(
                NoiseData263.CAVE_ENTRANCE.create(noises, enableFarlands), 0.75, 0.5);
        FloatNode263 bigEntrances = add(add(bigEntrance, new ConstantNode263(0.37F)),
                GradientNode263.yClamped(-10, 30, 0.3F, 0.0F));
        return cache(min(bigEntrances, add(spaghettiRoughness, spaghetti3D)));
    }

    private static FloatNode263 spaghetti2D(PositionalRandomFactory noises, boolean enableFarlands) {
        FloatNode263 rarityModulator = NoiseNode263.plain(
                NoiseData263.SPAGHETTI_2D_MODULATOR.create(noises, enableFarlands), 2.0, 1.0);
        FloatNode263 cave = raritySampler(rarityModulator, NoiseData263.SPAGHETTI_2D, noises, enableFarlands,
                new float[] { -0.75F, -0.5F, 0.5F, 0.75F }, new float[] { 0.5F, 0.75F, 1.0F, 2.0F, 3.0F });
        FloatNode263 elevationModulator = mappedNoise(NoiseData263.SPAGHETTI_2D_ELEVATION, noises,
                enableFarlands, 1.0, 0.0, -8.0F, 8.0F);
        FloatNode263 thicknessModulator = cache(mappedNoise(NoiseData263.SPAGHETTI_2D_THICKNESS, noises,
                enableFarlands, 2.0, 1.0, -0.6F, -1.3F));
        FloatNode263 slopedSpaghetti = unary(UnaryNode263.Type.ABS,
                add(cache(elevationModulator), GradientNode263.yClamped(-64, 320, 8.0F, -40.0F)));
        FloatNode263 layerRidged = unary(UnaryNode263.Type.CUBE, add(slopedSpaghetti, thicknessModulator));
        FloatNode263 caveNoise = add(cave, mul(thicknessModulator, new ConstantNode263(0.083F)));
        return clamp(max(caveNoise, layerRidged), -1.0F, 1.0F);
    }

    private static FloatNode263 pillars(PositionalRandomFactory noises, boolean enableFarlands) {
        FloatNode263 rare = mappedNoise(NoiseData263.PILLAR_RARENESS, noises, enableFarlands, 1.0, 1.0, 0.0F,
                -2.0F);
        FloatNode263 thickness = mappedNoise(NoiseData263.PILLAR_THICKNESS, noises, enableFarlands, 1.0, 1.0, 0.0F,
                1.1F);
        FloatNode263 source = NoiseNode263.plain(NoiseData263.PILLAR.create(noises, enableFarlands), 25.0, 0.3);
        return cache(mul(add(mul(source, new ConstantNode263(2.0F)), rare),
                unary(UnaryNode263.Type.CUBE, thickness)));
    }

    private static FloatNode263 noodle(PositionalRandomFactory noises, boolean enableFarlands, FloatNode263 y) {
        FloatNode263 toggle = yLimited(y,
                NoiseNode263.plain(NoiseData263.NOODLE.create(noises, enableFarlands), 1.0, 1.0), -60, 320, -1,
                4, 8);
        FloatNode263 thickness = yLimited(y,
                mappedNoise(NoiseData263.NOODLE_THICKNESS, noises, enableFarlands, 1.0, 1.0, -0.05F, -0.1F), -60,
                320, 0, 4, 8);
        FloatNode263 ridgeA = yLimited(y,
                NoiseNode263.plain(NoiseData263.NOODLE_RIDGE_A.create(noises, enableFarlands), 2.6666666666666665,
                        2.6666666666666665),
                -60, 320, 0, 4, 8);
        FloatNode263 ridgeB = yLimited(y,
                NoiseNode263.plain(NoiseData263.NOODLE_RIDGE_B.create(noises, enableFarlands), 2.6666666666666665,
                        2.6666666666666665),
                -60, 320, 0, 4, 8);
        FloatNode263 ridged = mul(max(unary(UnaryNode263.Type.ABS, ridgeA),
                unary(UnaryNode263.Type.ABS, ridgeB)), new ConstantNode263(1.5F));
        return new RangeChoiceNode263(toggle, -1000000.0F, 0.0F, new ConstantNode263(64.0F),
                add(thickness, ridged));
    }

    /**
     * 按分位数选一个缩放的噪声采样器。
     *
     * <p>阈值表升序，每个区间配一个 {@code 1/rarity} 缩放的噪声再乘回 rarity，最后取绝对值。这一组
     * 节点就是洞穴的「面条」半径分布。
     */
    private static FloatNode263 raritySampler(FloatNode263 input, NoiseData263 data,
            PositionalRandomFactory noises, boolean enableFarlands, float[] thresholds, float[] rarities) {
        FloatNode263[] functions = new FloatNode263[rarities.length];
        for (int i = 0; i < rarities.length; i++) {
            float rarity = rarities[i];
            functions[i] = mul(NoiseNode263.plain(data.create(noises, enableFarlands), 1.0 / rarity,
                    1.0 / rarity), new ConstantNode263(rarity));
        }
        return unary(UnaryNode263.Type.ABS, new IntervalSelectNode263(input, thresholds, functions));
    }

    private static FloatNode263 yLimited(FloatNode263 y, FloatNode263 whenInRange, int minY, int maxY,
            int whenOutOfRange, int cellSizeXz, int cellSizeY) {
        return new InterpolatedNode263(
                new RangeChoiceNode263(y, minY, maxY + 1, whenInRange, new ConstantNode263(whenOutOfRange)),
                cellSizeXz, cellSizeY);
    }

    private static FloatNode263 postProcess(FloatNode263 slide, int cellSizeXz, int cellSizeY) {
        FloatNode263 blended = mul(new BlendDensityNode263(slide), new ConstantNode263(0.64F));
        return unary(UnaryNode263.Type.SQUEEZE, new InterpolatedNode263(blended, cellSizeXz, cellSizeY));
    }

    /**
     * 上下两端的密度滑动。
     *
     * <p>顶部那一段对应 26.3 的 {@code slide} 上半段：从 {@code 320-80} 到 {@code 320-64}，即 240 到
     * 256，取值由 1 降到 0，插值目标是 {@code -0.078125F}。也就是 256 以上密度恒为负，即空气。
     *
     * <p>{@code topFadeEnabled} 为假时整段跳过，密度在 256 以上继续按噪声取值。26.3 没有这个开关，
     * 恒开；这里是本移植体的偏差，默认关。
     */
    private static FloatNode263 slideOverworld(FloatNode263 caves, boolean topFadeEnabled) {
        FloatNode263 value = caves;
        if (topFadeEnabled) {
            FloatNode263 topFactor = GradientNode263.yClamped(240, 256, 1.0F, 0.0F);
            value = new LerpNode263(topFactor, new ConstantNode263(-0.078125F), caves);
        }
        FloatNode263 bottomFactor = GradientNode263.yClamped(-64, -40, 0.0F, 1.0F);
        return new LerpNode263(bottomFactor, new ConstantNode263(0.1171875F), value);
    }

    private static FloatNode263 base3dNoise(PositionalRandomFactory noises, boolean enableFarlands) {
        return new BlendedNoiseNode263(
                BlendedNoise263.overworld(noises.fromHashOf("minecraft:terrain"), enableFarlands));
    }

    private static FloatNode263 noiseGradientDensity(FloatNode263 factor, FloatNode263 depthWithJaggedness) {
        return mul(unary(UnaryNode263.Type.QUARTER_NEGATIVE, mul(depthWithJaggedness, factor)),
                new ConstantNode263(4.0F));
    }

    /** 山脊折叠的节点版，与 {@link TerrainProvider263#peaksAndValleys} 的标量版同值。 */
    private static FloatNode263 peaksAndValleys(FloatNode263 weirdness) {
        FloatNode263 inner = add(unary(UnaryNode263.Type.ABS, weirdness), new ConstantNode263(-0.6666667F));
        return mul(add(unary(UnaryNode263.Type.ABS, inner), new ConstantNode263(-0.33333334F)),
                new ConstantNode263(-3.0F));
    }

    private static FloatNode263 shiftedXz(NoiseData263 data, PositionalRandomFactory noises,
            boolean enableFarlands, FloatNode263 shiftX, FloatNode263 shiftZ) {
        return NoiseNode263.shiftedXz(data.create(noises, enableFarlands), 0.25, 0.0, shiftX, shiftZ);
    }

    /**
     * 样条与混合目标的插值。
     *
     * <p>26.3 把 blendAlpha 当权重，无 blender 时它是 1.0F，插值因此恒取样条那一侧。这里按常量落，
     * 拓扑保留，值等于样条本身。
     */
    private static FloatNode263 blended(FloatNode263 splineValue, FloatNode263 blendingTarget) {
        return cache(new LerpNode263(new ConstantNode263(1.0F), blendingTarget, splineValue));
    }

    /** {@code remap} 是组合式而不是节点，照 26.3 的两行展开。 */
    private static FloatNode263 remap(FloatNode263 input, float fromMin, float fromMax, float toMin,
            float toMax) {
        float factor = (toMax - toMin) / (fromMax - fromMin);
        float offset = toMin - fromMin * factor;
        FloatNode263 scaled = mul(input, new ConstantNode263(factor));
        return offset == 0.0F ? scaled : add(scaled, new ConstantNode263(offset));
    }

    private static FloatNode263 mappedNoise(NoiseData263 data, PositionalRandomFactory noises,
            boolean enableFarlands, double xzScale, double yScale, float minTarget, float maxTarget) {
        return remap(NoiseNode263.plain(data.create(noises, enableFarlands), xzScale, yScale), -1.0F, 1.0F,
                minTarget, maxTarget);
    }

    private static FloatNode263 add(FloatNode263 left, FloatNode263 right) {
        return new BinaryNode263(BinaryNode263.Type.ADD, left, right);
    }

    private static FloatNode263 mul(FloatNode263 left, FloatNode263 right) {
        return new BinaryNode263(BinaryNode263.Type.MUL, left, right);
    }

    private static FloatNode263 min(FloatNode263 left, FloatNode263 right) {
        return new BinaryNode263(BinaryNode263.Type.MIN, left, right);
    }

    private static FloatNode263 max(FloatNode263 left, FloatNode263 right) {
        return new BinaryNode263(BinaryNode263.Type.MAX, left, right);
    }

    private static FloatNode263 clamp(FloatNode263 input, float min, float max) {
        return new ClampNode263(input, min, max);
    }

    private static FloatNode263 unary(UnaryNode263.Type type, FloatNode263 input) {
        return new UnaryNode263(type, input);
    }

    private static FloatNode263 cache(FloatNode263 input) {
        return new CacheNode263(input);
    }
}
