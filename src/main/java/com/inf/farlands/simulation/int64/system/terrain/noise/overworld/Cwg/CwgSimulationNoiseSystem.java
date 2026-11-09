package com.inf.farlands.simulation.int64.system.terrain.noise.overworld.Cwg;

import com.inf.farlands.simulation.int64.SimulationNoiseSystem;
import com.inf.farlands.simulation.int64.noise.Aquifer;
import com.inf.farlands.simulation.int64.noise.Density;
import com.inf.farlands.simulation.int64.noise.FluidPicker;
import com.inf.farlands.simulation.int64.noise.FluidStatus;
import com.inf.farlands.simulation.int64.noise.Random;
import com.inf.farlands.simulation.int64.noise.Router;
import com.inf.farlands.simulation.int64.noise.Settings;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 模拟层的 Cwg 系统：四路八度噪声加一条密度链，参数全部硬编码。
 *
 * <p>与地形层的同名系统共享同一套算法，两个要求划定了它的边界：
 *
 * <ul>
 * <li>Cwg 内部算法，即四路噪声与密度链，必须与地形层逐位一致。噪声段是 CwgSimulationNoise，密度链是
 * CwgSimulationDensity，两者都是从地形层复制过来的，类体除包名与类名外未改。</li>
 * <li>路由与形状样条不在一致的范围内。模拟层没有与地形层同源的 continents、erosion、ridges，
 * 所以 height 与 volatility 由本类给两个常量，见 HEIGHT_SOURCE 与 FACTOR_SOURCE。</li>
 * </ul>
 *
 * <p>穹顶仍按 int32 的索引上界形成：噪声段不折叠，格点索引在 |F| >= 2^31 处饱和，权重离开 [0, 1]，
 * 混合式的零集退化成一张平面。模拟层的坐标本来就在 int 全域之外，所以这一层落在穹顶的深处；
 * 冻结是按阶逐步发生的，第 8 阶起先在模拟层起点处已冻结，最低阶要到约 1.4e12 格才冻结。
 *
 * <p>cell 网格取采样格本身：noiseSizeXZ 与 noiseSizeY 分别是采样格除以 4，即 cell 边长 4 与 8 方块。
 * 密度链实现 InterpolatedDensity，由填充器按 cell 采样再块内插值。
 */
public final class CwgSimulationNoiseSystem implements SimulationNoiseSystem {

    /** 硬编码的种子。四路噪声的种子由它派生，顺序固定。 */
    private static final long SEED = 1119L;

    /** 四路噪声的基准频率，与地形层该系统的默认参数同值。 */
    private static final double SELECTOR_FREQUENCY_XZ = (684.412 / 80.0 / 128.0) / 4.0;
    private static final double SELECTOR_FREQUENCY_Y = (684.412 / 80.0 / 128.0) / 8.0;
    private static final double LOW_HIGH_FREQUENCY_XZ = (684.412 / 32768.0) / 4.0;
    private static final double LOW_HIGH_FREQUENCY_Y = (684.412 / 32768.0) / 8.0;
    private static final double DEPTH_FREQUENCY_XZ = (200.0 / 32768.0) / 4.0;

    /** 八度数，与地形层该系统的默认参数同值。depth 只有水平两轴。 */
    private static final int SELECTOR_OCTAVES = 8;
    private static final int LOW_OCTAVES = 16;
    private static final int HIGH_OCTAVES = 16;
    private static final int DEPTH_OCTAVES = 16;

    /** 采样格边长，与地形层该系统的默认参数同值。cell 边长等于采样格。 */
    private static final int SAMPLE_SIZE_XZ = 4;
    private static final int SAMPLE_SIZE_Y = 8;

    /**
     * 形状样条的替代输入。地形层这里是两条样条在路由的 continents、erosion、ridges 上的取值，
     * 模拟层没有同源输入，因此取常量：0 与 1 分别给出 height 64 与 volatility 64。
     */
    private static final double HEIGHT_SOURCE = 0.0;
    private static final double FACTOR_SOURCE = 1.0;

    private final CwgSimulationNoise selector;
    private final CwgSimulationNoise low;
    private final CwgSimulationNoise high;
    private final CwgSimulationNoise depth;

    public CwgSimulationNoiseSystem() {
        // 四个种子按顺序取自同一条随机流，顺序换了四路噪声互换。
        java.util.Random random = new java.util.Random(SEED);
        // 不折叠：折叠会把坐标折回 int32 范围，饱和永不发生，穹顶随之消失。
        this.selector = new CwgSimulationNoise(SELECTOR_FREQUENCY_XZ, SELECTOR_FREQUENCY_Y,
                SELECTOR_FREQUENCY_XZ, SELECTOR_OCTAVES, CwgSimulationNoise.foldSeed(random.nextLong()), false);
        this.low = new CwgSimulationNoise(LOW_HIGH_FREQUENCY_XZ, LOW_HIGH_FREQUENCY_Y, LOW_HIGH_FREQUENCY_XZ,
                LOW_OCTAVES, CwgSimulationNoise.foldSeed(random.nextLong()), false);
        this.high = new CwgSimulationNoise(LOW_HIGH_FREQUENCY_XZ, LOW_HIGH_FREQUENCY_Y, LOW_HIGH_FREQUENCY_XZ,
                HIGH_OCTAVES, CwgSimulationNoise.foldSeed(random.nextLong()), false);
        // depth 只有水平两轴，竖直频率为 0，因此不参与竖直方向的饱和。
        this.depth = new CwgSimulationNoise(DEPTH_FREQUENCY_XZ, 0.0, DEPTH_FREQUENCY_XZ, DEPTH_OCTAVES,
                CwgSimulationNoise.foldSeed(random.nextLong()), false);
    }

    @Override
    public Density createFinalDensity(Router router) {
        return new CwgSimulationDensity(this.selector, this.low, this.high, this.depth,
                HEIGHT_SOURCE, FACTOR_SOURCE);
    }

    /** cell 网格按采样格换算：cell 边长是采样格乘 4，水平只有一个分量。 */
    @Override
    public int noiseSizeXZ() {
        return SAMPLE_SIZE_XZ / 4;
    }

    @Override
    public int noiseSizeY() {
        return SAMPLE_SIZE_Y / 4;
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
