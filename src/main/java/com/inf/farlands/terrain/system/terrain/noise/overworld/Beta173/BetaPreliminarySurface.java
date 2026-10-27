package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.util.world.WorldBounds;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * beta 1.7.3 地形的表面高度，交给含水层与地表规则。
 *
 * <p>
 * 为什么要自己算：含水层的水线、地表规则的 {@code above_preliminary_surface}、深暗判定三处都从路由上的
 * {@code preliminarySurfaceLevel} 取表面。沿用宿主那一份时，密度是 beta 的、表面是宿主地形的，于是水按一份
 * 与地形无关的表面灌出来，地表层也按它错位。
 *
 * <p>
 * 算法是解 {@code density = 0}。对固定的气候与高度项，密度是 cell Y 的分段线性单调函数：
 * <pre>
 * density = h - (cy - m) * 12 / a      (cy >= m)
 * density = h - (cy - m) * 12 / a * 4  (cy < m)
 * </pre>
 * 于是 {@code h >= 0} 时 {@code cy = m + h * a / 12}，{@code h < 0} 时 {@code cy = m + h * a / 48}。
 * 高度项 {@code h} 本身依赖 cell Y，因为通道 0 与 1 是 3D 八度，所以做两次不动点：起点取 {@code m}，
 * 每次用上一轮解出的 Y 取该 cell 的高度项。两步足够，因为一格的 {@code h} 变化被 {@code a / 12 >= 1/24}
 * cell 的斜率压住。
 *
 * <p>
 * 值域：{@code m ∈ [7.74, 9.03]} cell、{@code h ∈ [-128, 128]} 方块、{@code a ∈ [0.5, 1.5]}，代入得表面落在
 * 30 到 200 之间。所以它与世界的坐标无关，极端 Y 处 beta 密度恒为正、没有零点截面。仍按可玩界夹一次，
 * 防下游的 {@code +8} 与 {@code +12} 越界。
 *
 * <p>
 * 群系布局在构造期取：本类的 {@code compute} 会在含水层与地表规则里被调用，那时
 * {@code TerrainSystemContext} 已经清掉，不许再读。
 */
final class BetaPreliminarySurface implements DensityFunction.SimpleFunction {

    /** beta 按列取温度湿度，固定在海平面那一层，与 {@link BetaDensityFunction} 同值。 */
    private static final int SEA_LEVEL_QUART = QuartPos.fromBlock(63);

    /** beta 的 cell 尺寸，方块。 */
    private static final int CELL_WIDTH = 4;

    private static final int CELL_HEIGHT = 8;

    /** 不动点迭代次数。 */
    private static final int ITERATIONS = 2;

    /** 夹取余量，留给含水层的 {@code +8} 与 {@code +12}。 */
    private static final int CLAMP_MARGIN = 16;

    private final BetaTerrainNoise noise;

    private final BiomeSystem.BiomeLayout layout;

    BetaPreliminarySurface(BetaTerrainNoise noise, BiomeSystem.BiomeLayout layout) {
        this.noise = noise;
        this.layout = layout;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int blockX = context.blockX();
        int blockZ = context.blockZ();
        int cellX = Math.floorDiv(blockX, CELL_WIDTH);
        int cellZ = Math.floorDiv(blockZ, CELL_WIDTH);

        Holder<Biome> holder = this.layout.source().getNoiseBiome(QuartPos.fromBlock(blockX),
                SEA_LEVEL_QUART, QuartPos.fromBlock(blockZ), this.layout.sampler());
        Biome biome = holder.value();
        BetaTerrainFormula.Climate climate = BetaTerrainFormula.climate(
                this.noise.sample(3, (double) cellX, 10.0, (double) cellZ),
                this.noise.sample(4, (double) cellX, 10.0, (double) cellZ),
                biome.getBaseTemperature(), BetaDensityFunction.downfall(biome));

        double surface = climate.midCell() * CELL_HEIGHT;
        for (int i = 0; i < ITERATIONS; i++) {
            int cellY = Math.floorDiv((int) Math.floor(surface), CELL_HEIGHT);
            double height = BetaTerrainFormula.height(cellX * CELL_WIDTH, cellY * CELL_HEIGHT,
                    cellZ * CELL_WIDTH, this.noise);
            // 中心以下陡峭度是四倍，两根的斜率因此差四倍。
            double cells = height >= 0.0 ? height * climate.factor() / 12.0
                    : height * climate.factor() / 48.0;
            surface = (climate.midCell() + cells) * CELL_HEIGHT;
        }
        return Math.max((double) WorldBounds.MIN_PLAYABLE_BLOCK + CLAMP_MARGIN,
                Math.min((double) WorldBounds.MAX_PLAYABLE_BLOCK - CLAMP_MARGIN, surface));
    }

    @Override
    public double minValue() {
        return (double) WorldBounds.MIN_PLAYABLE_BLOCK + CLAMP_MARGIN;
    }

    @Override
    public double maxValue() {
        return (double) WorldBounds.MAX_PLAYABLE_BLOCK - CLAMP_MARGIN;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
