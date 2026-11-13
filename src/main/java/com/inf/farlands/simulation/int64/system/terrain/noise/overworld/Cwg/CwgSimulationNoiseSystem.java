package com.inf.farlands.simulation.int64.system.terrain.noise.overworld.Cwg;

import java.util.Set;

import com.inf.farlands.simulation.int64.SimulationNoiseSystem;
import com.inf.farlands.simulation.int64.noise.Aquifer;
import com.inf.farlands.simulation.int64.noise.Density;
import com.inf.farlands.simulation.int64.noise.FluidPicker;
import com.inf.farlands.simulation.int64.noise.FluidStatus;
import com.inf.farlands.simulation.int64.noise.Random;
import com.inf.farlands.simulation.int64.noise.Router;
import com.inf.farlands.simulation.int64.noise.Settings;
import com.inf.farlands.terrain.registry.SeedSession;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 模拟层的 Cwg 系统：四路八度噪声加一条密度链，参数全部来自声明。
 *
 * <p>
 * 参数面与校验逐条照抄 farlands 的 {@code CwgNoiseSystem}：同样的键名、同样的字面默认值、同样的三类单参数
 * 判据与一条跨字段规则，seed 与 farlands 一样没有字面默认，只有空框随机关键字。穹顶的成因也在照抄之列：
 * {@code farLands} 打开时坐标不折叠，越过 2^31 后格点索引冻结，插值权重离开 [0,1]，零集退化成一张平面；
 * 关掉折叠后坐标被折回 int32 范围，饱和不发生，退化成普通重复噪声。
 *
 * <p>
 * 与 farlands 那份只有两处差别，都是载体上的：形状换算的 height 与 volatility 由两个常量给，因为模拟层没有
 * 与地形层同源的 continents、erosion、ridges 样条；液体与含水层两侧都恒空气。
 *
 * <p>
 * 无状态：字段全部 final，构造完成后只读。填充任务跑在 {@code farlands-sim} 上，多个任务共用同一个实例。
 */
public final class CwgSimulationNoiseSystem implements SimulationNoiseSystem {

    /** 水平方向每 4 格一个 cell，竖直方向每 8 格一个，故频率除 4 与 8。 */
    private static final double SELECTOR_FREQUENCY_XZ = (684.412 / 80.0 / 128.0) / 4.0;
    private static final double SELECTOR_FREQUENCY_Y = (684.412 / 80.0 / 128.0) / 8.0;
    private static final double LOW_HIGH_FREQUENCY_XZ = (684.412 / 32768.0) / 4.0;
    private static final double LOW_HIGH_FREQUENCY_Y = (684.412 / 32768.0) / 8.0;
    private static final double DEPTH_FREQUENCY_XZ = (200.0 / 32768.0) / 4.0;

    /**
     * 形状样条的替代输入。farlands 那里是两条二维样条在路由的 continents、erosion、ridges 上的取值，
     * 模拟层没有同源输入，所以取常量：0 与 1 分别给出 height 64 与 volatility 64。
     */
    private static final double HEIGHT_SOURCE = 0.0;

    private static final double FACTOR_SOURCE = 1.0;

    /**
     * 声明：四路噪声各六项，depth 没有竖直频率，采样式三项，形状换算五项，加开关与 seed。
     *
     * <p>
     * 三类单参数判据写在各自的 spec 上，界面逐键标红：频率必须为正且有限、octave 数在 1 到 30 之间、
     * 采样格边长只能取 4、8、16 之一。两个水平采样格必须相等是跨字段判据，写在 {@code withConstraint} 里。
     *
     * <p>
     * 键名在声明与读取两处各出现一次，两处不一致会在构造时以缺参数抛出，不会静默取默认值。
     * {@code noiseSampleSizeZ} 只声明、不被构造器读取：横向 cell 只用 X，Z 由跨字段规则保证与 X 相等。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofBoolean("farLands").define(true).build(),
            SeedSession.seedParam(),
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
                        : Component.translatable("createWorld.tab.infs-farlands.param.error.sample_xz_equal", x, z);
            }));

    /** 采样格边长，只允许 4、8、16，其它取值会让 cell 边长不整除 16。 */
    private static SystemParamSpec sampleSize(String key, int value) {
        return SystemParamSpec.ofInt(key).define(value)
                .check(v -> v == 4 || v == 8 || v == 16,
                        v -> Component.translatable("createWorld.tab.infs-farlands.param.error.sample_size", key, v))
                .build();
    }

    /** 频率必须为正的有限值。0 会让该路恒定，NaN 会让比较全部为假。 */
    private static SystemParamSpec frequency(String key, double value) {
        return SystemParamSpec.ofDouble(key).define(value)
                .check(v -> v > 0.0 && !Double.isInfinite(v),
                        v -> Component.translatable("createWorld.tab.infs-farlands.param.error.frequency", key, v))
                .build();
    }

    /** 八度数，超出这个区间时叠加结果不可用。 */
    private static SystemParamSpec octaves(String key, int value) {
        return SystemParamSpec.ofInt(key).define(value)
                .check(v -> v >= 1 && v <= 30,
                        v -> Component.translatable("createWorld.tab.infs-farlands.param.error.octaves", key, v))
                .build();
    }

    private final CwgSimulationNoise selector;
    private final CwgSimulationNoise low;
    private final CwgSimulationNoise high;
    private final CwgSimulationNoise depth;
    private final CwgSimulationDensity.Ops ops;
    private final int noiseSampleSizeX;
    private final int noiseSampleSizeY;

    /** 整条路由的缓存。写序先结果后键，读序先键后结果，读到半个状态只会重建一次。 */
    private volatile Router cachedRouter;

    private volatile Router cachedVanilla;

    public CwgSimulationNoiseSystem(SystemArgs args) {
        // 开关与折叠是反的：farLands 打开才不折叠，穹顶才形成。
        boolean foldCoordinates = !args.getBoolean("farLands");
        this.noiseSampleSizeX = args.getInt("noiseSampleSizeX");
        this.noiseSampleSizeY = args.getInt("noiseSampleSizeY");
        // 四个种子按顺序取自同一条随机流，顺序换了四路噪声互换。
        java.util.Random random = new java.util.Random(args.getLong("seed"));
        this.selector = new CwgSimulationNoise(args.getDouble("selectorNoiseFrequencyX"),
                args.getDouble("selectorNoiseFrequencyY"), args.getDouble("selectorNoiseFrequencyZ"),
                args.getInt("selectorNoiseOctaves"), CwgSimulationNoise.foldSeed(random.nextLong()), foldCoordinates);
        this.low = new CwgSimulationNoise(args.getDouble("lowNoiseFrequencyX"),
                args.getDouble("lowNoiseFrequencyY"), args.getDouble("lowNoiseFrequencyZ"),
                args.getInt("lowNoiseOctaves"), CwgSimulationNoise.foldSeed(random.nextLong()), foldCoordinates);
        this.high = new CwgSimulationNoise(args.getDouble("highNoiseFrequencyX"),
                args.getDouble("highNoiseFrequencyY"), args.getDouble("highNoiseFrequencyZ"),
                args.getInt("highNoiseOctaves"), CwgSimulationNoise.foldSeed(random.nextLong()), foldCoordinates);
        // depth 只有水平两轴，竖直频率为 0，因此不参与竖直方向的饱和。
        this.depth = new CwgSimulationNoise(args.getDouble("depthNoiseFrequencyX"), 0.0,
                args.getDouble("depthNoiseFrequencyZ"), args.getInt("depthNoiseOctaves"),
                CwgSimulationNoise.foldSeed(random.nextLong()), foldCoordinates);

        this.ops = new CwgSimulationDensity.Ops(args.getDouble("selectorNoiseFactor"),
                args.getDouble("selectorNoiseOffset"), args.getDouble("lowNoiseFactor"),
                args.getDouble("lowNoiseOffset"), args.getDouble("highNoiseFactor"),
                args.getDouble("highNoiseOffset"), args.getDouble("depthNoiseFactor"),
                args.getDouble("depthNoiseOffset"), args.getDouble("heightFactor"),
                args.getDouble("heightOffset"), args.getDouble("heightVariationFactor"),
                args.getDouble("heightVariationOffset"),
                args.getDouble("specialHeightVariationFactorBelowAverageY"));
    }

    @Override
    public Density createFinalDensity(Router router) {
        return new CwgSimulationDensity(this.selector, this.low, this.high, this.depth, HEIGHT_SOURCE, FACTOR_SOURCE,
                this.ops);
    }

    /**
     * 整条路由只换 finalDensity，其余字段照抄。填充器每段调一次，所以按路由身份缓存。
     *
     * <p>模拟层的入参路由是常量，缓存恒定命中；保留它不是为了这里的性能，而是为了让本类与 farlands 的
     * {@code CwgNoiseSystem} 逐行可对照。
     */
    @Override
    public Router createRouter(Router vanilla) {
        if (this.cachedVanilla == vanilla) {
            Router cached = this.cachedRouter;
            if (cached != null) {
                return cached;
            }
        }
        Router built = vanilla.withFinalDensity(this.createFinalDensity(vanilla));
        this.cachedRouter = built;
        this.cachedVanilla = vanilla;
        return built;
    }

    /** cell 网格按采样格换算：cell 边长是采样格乘 4，水平只有一个分量。 */
    @Override
    public int noiseSizeXZ() {
        return this.noiseSampleSizeX / 4;
    }

    @Override
    public int noiseSizeY() {
        return this.noiseSampleSizeY / 4;
    }

    /** 液面取 MIN_VALUE 恒出空气。否则密度非正的留空半边会被默认 picker 填成水或岩浆。 */
    @Override
    public FluidPicker createFluidPicker(Settings settings) {
        return (x, y, z) -> new FluidStatus(Long.MIN_VALUE, Blocks.AIR.defaultBlockState());
    }

    /** 密度为正交给默认方块，非正接管成空气。与 picker 同义，省掉含水层的网格取样。 */
    @Override
    public Aquifer createAquifer(Settings settings, long sectionX, long sectionY, long sectionZ, Router router,
            Random random, FluidPicker picker) {
        return new Aquifer() {

            @Override
            public BlockState computeSubstance(long x, long y, long z, double density) {
                return density > 0.0 ? null : Blocks.AIR.defaultBlockState();
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return false;
            }
        };
    }
}
