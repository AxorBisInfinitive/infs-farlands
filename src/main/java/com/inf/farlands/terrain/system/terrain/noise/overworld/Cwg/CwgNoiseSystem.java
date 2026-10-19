package com.inf.farlands.terrain.system.terrain.noise.overworld.Cwg;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData.Arg;

import net.minecraft.core.Holder;
import net.minecraft.data.worldgen.TerrainProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 自研八度噪声的地形系统，四路八度加两条形状样条拼成一条密度链。
 *
 * <p>farLands 打开时坐标不做折叠，越过 2^31 后格点索引冻结，密度在顶点区退化成一片平面，
 * 三条轴都越过门槛的位置就是分界平面出现的地方。关掉折叠后坐标被折回 int32 范围，饱和不会
 * 发生，退化成普通的重复噪声。
 *
 * <p>竖直方向的频率是水平方向的一半，所以竖直门槛是水平的两倍，顶点区是八分体而不是立方体。
 * 采样格与水平门槛同源，改小采样格会让 cell 不足以覆盖一个门槛周期。
 *
 * <p>height 与 volatility 取自两条二维形状样条，与群系数据无关；样条来自传入的 vanilla 路由，
 * 因此实例构造时不需要额外的世界上下文。链构建一次后按 vanilla 路由的身份缓存。
 */
public final class CwgNoiseSystem implements NoiseSystem {

    /** 水平方向每 4 格一个 cell，竖直方向每 8 格一个，故频率除 4 与 8。 */
    private static final double SELECTOR_FREQUENCY_XZ = (684.412 / 80.0 / 128.0) / 4.0;
    private static final double SELECTOR_FREQUENCY_Y = (684.412 / 80.0 / 128.0) / 8.0;
    private static final double LOW_HIGH_FREQUENCY_XZ = (684.412 / 32768.0) / 4.0;
    private static final double LOW_HIGH_FREQUENCY_Y = (684.412 / 32768.0) / 8.0;
    private static final double DEPTH_FREQUENCY_XZ = (200.0 / 32768.0) / 4.0;

    /**
     * 声明：四路噪声各六项，depth 没有竖直频率，采样式三项，形状换算五项，加开关与 seed。
     *
     * <p>
     * 三类单参数判据写在各自的 spec 上，界面逐键标红：频率必须为正且有限、octave 数在 1 到 30 之间、
     * 采样格边长只能取 4、8、16 之一。两个水平采样格必须相等是跨字段判据，写在 withConstraint 里。
     *
     * <p>
     * 键名在声明与读取两处各出现一次，两处不一致会在构造时以缺参数抛出，不会静默取默认值。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofBoolean("farLands").define(true).build(),
            SystemParamSpec.ofLong("seed")
                    .keyword("", () -> Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                            "createWorld.tab.infs-farlands.param.seed.random")
                    .build(),
            sampleSize("noiseSampleSizeX", 4),
            sampleSize("noiseSampleSizeY", 8),
            sampleSize("noiseSampleSizeZ", 4),

            SystemParamSpec.ofDouble("selectorNoiseFactor").define(12.75).build(),
            SystemParamSpec.ofDouble("selectorNoiseOffset").define(0.5).build(),
            frequency("selectorNoiseFrequencyX", SELECTOR_FREQUENCY_XZ),
            frequency("selectorNoiseFrequencyY", SELECTOR_FREQUENCY_Y),
            frequency("selectorNoiseFrequencyZ", SELECTOR_FREQUENCY_XZ),
            octaves("selectorNoiseOctaves", 8),

            SystemParamSpec.ofDouble("lowNoiseFactor").define(1.0).build(),
            SystemParamSpec.ofDouble("lowNoiseOffset").define(0.0).build(),
            frequency("lowNoiseFrequencyX", LOW_HIGH_FREQUENCY_XZ),
            frequency("lowNoiseFrequencyY", LOW_HIGH_FREQUENCY_Y),
            frequency("lowNoiseFrequencyZ", LOW_HIGH_FREQUENCY_XZ),
            octaves("lowNoiseOctaves", 16),

            SystemParamSpec.ofDouble("highNoiseFactor").define(1.0).build(),
            SystemParamSpec.ofDouble("highNoiseOffset").define(0.0).build(),
            frequency("highNoiseFrequencyX", LOW_HIGH_FREQUENCY_XZ),
            frequency("highNoiseFrequencyY", LOW_HIGH_FREQUENCY_Y),
            frequency("highNoiseFrequencyZ", LOW_HIGH_FREQUENCY_XZ),
            octaves("highNoiseOctaves", 16),

            SystemParamSpec.ofDouble("depthNoiseFactor").define(1.024).build(),
            SystemParamSpec.ofDouble("depthNoiseOffset").define(0.0).build(),
            frequency("depthNoiseFrequencyX", DEPTH_FREQUENCY_XZ),
            frequency("depthNoiseFrequencyZ", DEPTH_FREQUENCY_XZ),
            octaves("depthNoiseOctaves", 16),

            SystemParamSpec.ofDouble("heightFactor").define(64.0).build(),
            SystemParamSpec.ofDouble("heightOffset").define(64.0).build(),
            SystemParamSpec.ofDouble("heightVariationFactor").define(64.0).build(),
            SystemParamSpec.ofDouble("heightVariationOffset").define(0.0).build(),
            SystemParamSpec.ofDouble("specialHeightVariationFactorBelowAverageY").define(0.25).build())
            .withConstraint(SystemParams.rule(Set.of("noiseSampleSizeX", "noiseSampleSizeZ"), args -> {
                int x = args.getInt("noiseSampleSizeX");
                int z = args.getInt("noiseSampleSizeZ");
                return x == z ? null
                        : Component.translatable("createWorld.tab.infs-farlands.param.error.sample_xz_equal",
                                x, z);
            }));

    /** 采样格边长，只允许 4、8、16，其它取值会让 cell 边长不整除 16。 */
    private static SystemParamSpec sampleSize(String key, int value) {
        return SystemParamSpec.ofInt(key).define(value)
                .check(v -> v == 4 || v == 8 || v == 16,
                        v -> Component.translatable("createWorld.tab.infs-farlands.param.error.sample_size",
                                key, v))
                .build();
    }

    /** 频率必须为正的有限值。0 会让该路恒定，NaN 会让比较全部为假。 */
    private static SystemParamSpec frequency(String key, double value) {
        return SystemParamSpec.ofDouble(key).define(value)
                .check(v -> v > 0.0 && !Double.isInfinite(v),
                        v -> Component.translatable("createWorld.tab.infs-farlands.param.error.frequency",
                                key, v))
                .build();
    }

    /** 八度数，超出这个区间时叠加结果不可用。 */
    private static SystemParamSpec octaves(String key, int value) {
        return SystemParamSpec.ofInt(key).define(value)
                .check(v -> v >= 1 && v <= 30,
                        v -> Component.translatable("createWorld.tab.infs-farlands.param.error.octaves",
                                key, v))
                .build();
    }

    private final CwgNoise selector;
    private final CwgNoise low;
    private final CwgNoise high;
    private final CwgNoise depth;
    private final CwgDensity.Ops ops;
    private final int noiseSampleSizeX;
    private final int noiseSampleSizeY;

    /** 按 vanilla 路由身份缓存的整条路由。写序先结果后键，读序先键后结果。 */
    private volatile NoiseRouter cachedRouter;
    private volatile NoiseRouter cachedVanilla;

    public CwgNoiseSystem(SystemArgs args) {
        boolean foldCoordinates = !args.getBoolean("farLands");

        this.noiseSampleSizeX = args.getInt("noiseSampleSizeX");
        this.noiseSampleSizeY = args.getInt("noiseSampleSizeY");

        // 四个种子按顺序取自同一条随机流，顺序换了四路噪声互换。
        Random random = new Random(args.getLong("seed"));
        this.selector = new CwgNoise(
                args.getDouble("selectorNoiseFrequencyX"),
                args.getDouble("selectorNoiseFrequencyY"),
                args.getDouble("selectorNoiseFrequencyZ"),
                args.getInt("selectorNoiseOctaves"),
                CwgNoise.foldSeed(random.nextLong()), foldCoordinates);
        this.low = new CwgNoise(
                args.getDouble("lowNoiseFrequencyX"),
                args.getDouble("lowNoiseFrequencyY"),
                args.getDouble("lowNoiseFrequencyZ"),
                args.getInt("lowNoiseOctaves"),
                CwgNoise.foldSeed(random.nextLong()), foldCoordinates);
        this.high = new CwgNoise(
                args.getDouble("highNoiseFrequencyX"),
                args.getDouble("highNoiseFrequencyY"),
                args.getDouble("highNoiseFrequencyZ"),
                args.getInt("highNoiseOctaves"),
                CwgNoise.foldSeed(random.nextLong()), foldCoordinates);
        // depth 只有水平两轴，竖直频率为 0，因此不参与竖直方向的饱和。
        this.depth = new CwgNoise(
                args.getDouble("depthNoiseFrequencyX"),
                0.0,
                args.getDouble("depthNoiseFrequencyZ"),
                args.getInt("depthNoiseOctaves"),
                CwgNoise.foldSeed(random.nextLong()), foldCoordinates);

        this.ops = new CwgDensity.Ops(
                args.getDouble("selectorNoiseFactor"), args.getDouble("selectorNoiseOffset"),
                args.getDouble("lowNoiseFactor"), args.getDouble("lowNoiseOffset"),
                args.getDouble("highNoiseFactor"), args.getDouble("highNoiseOffset"),
                args.getDouble("depthNoiseFactor"), args.getDouble("depthNoiseOffset"),
                args.getDouble("heightFactor"), args.getDouble("heightOffset"),
                args.getDouble("heightVariationFactor"), args.getDouble("heightVariationOffset"),
                args.getDouble("specialHeightVariationFactorBelowAverageY"));
    }

    /**
     * 链外面套一层插值标记。没有它，NoiseChunk 里没有插值器，每个方块都会完整算一遍四路八度，
     * 成本高两个数量级；套上之后每个 cell 只算八个角，cell 内部由上层线性插值。
     */
    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        DensityFunction ridgesFolded = peaksAndValleys(router.ridges());
        DensityFunction offsetSpline = DensityFunctions.spline(TerrainProvider.overworldOffset(
                coordinate(router.continents()), coordinate(router.erosion()), coordinate(ridgesFolded), false));
        DensityFunction factorSpline = DensityFunctions.spline(TerrainProvider.overworldFactor(
                coordinate(router.continents()), coordinate(router.erosion()), coordinate(router.ridges()),
                coordinate(ridgesFolded), false));
        return DensityFunctions.interpolated(new CwgDensity(this.selector, this.low, this.high, this.depth,
                offsetSpline, factorSpline, this.ops));
    }

    /**
     * 整条路由换掉 finalDensity，其余字段照抄 vanilla。NoiseChunk 每个段构造一次，本方法每段都
     * 会被调到，所以按路由身份缓存；读到半个状态只会重建一次，不会拿到错的链。
     */
    @Override
    public NoiseRouter createRouter(NoiseRouter vanilla) {
        if (this.cachedVanilla == vanilla) {
            NoiseRouter cached = this.cachedRouter;
            if (cached != null) {
                return cached;
            }
        }
        NoiseRouter built = new NoiseRouter(
                vanilla.barrierNoise(), vanilla.fluidLevelFloodednessNoise(), vanilla.fluidLevelSpreadNoise(),
                vanilla.lavaNoise(), vanilla.temperature(), vanilla.vegetation(), vanilla.continents(),
                vanilla.erosion(), vanilla.depth(), vanilla.ridges(), vanilla.preliminarySurfaceLevel(),
                createFinalDensity(vanilla), vanilla.veinToggle(), vanilla.veinRidged(), vanilla.veinGap());
        this.cachedRouter = built;
        this.cachedVanilla = vanilla;
        return built;
    }

    /** cell 网格按采样格换算：cell 边长是采样格乘 4，水平只有一个分量。 */
    @Override
    public int[] noiseSize() {
        return new int[] { this.noiseSampleSizeX / 4, this.noiseSampleSizeY / 4 };
    }

    /** 液面取 MIN_VALUE 恒出空气。否则密度非正的留空半边会被默认 picker 填成水或岩浆。 */
    @Override
    public Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        return (x, y, z) -> new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
    }

    /** 密度为正交给默认方块，非正接管成空气。与 picker 同义，省掉 aquifer 的网格取样。 */
    @Override
    public Aquifer createAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        return new Aquifer() {
            @Override
            public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
                return substance > 0.0 ? null : Blocks.AIR.defaultBlockState();
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return false;
            }
        };
    }

    /** 与 vanilla 的折返定义一致，用于把原始脊噪声折成另一条样条的坐标。 */
    private static DensityFunction peaksAndValleys(DensityFunction weirdness) {
        return DensityFunctions.mul(
                DensityFunctions.add(
                        DensityFunctions.add(weirdness.abs(), DensityFunctions.constant(-0.6666666666666666)).abs(),
                        DensityFunctions.constant(-0.3333333333333333)),
                DensityFunctions.constant(-3.0));
    }

    private static DensityFunctions.Spline.Coordinate coordinate(DensityFunction function) {
        return new DensityFunctions.Spline.Coordinate(Holder.direct(function));
    }
}
