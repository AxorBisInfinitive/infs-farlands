package com.inf.farlands.terrain.system.terrain.noise.theEnd.Layers;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

/**
 * 末地分层噪声系统：把 theEnd 的 vanilla 链按竖直周期重复，每层一套种子。
 *
 * <p>
 * 每层就是一份完整的末地链，只把 Y 折进 {@code [0, period)}：岛函数、base_3d_noise 与两段滑动都用
 * 本地 Y 求值，层种子由系统 seed 与层号派生。所以每个窗口里地形只出现在本地 4 到 312 格，与 y=0 那层
 * 同形，层与层之间是空气。
 *
 * <p>
 * {@code interpolated} 包在分层节点外面，见 {@link LayerDensity} 的类注释。本系统与 theEnd 的 vanilla
 * 系统一样不读宿主路由：密度、方块与流体都按末地的口径自带。
 */
public final class LayersNoiseSystem implements NoiseSystem {

    /** cell 宽 8 格、高 4 格，与末地原版 noise 尺寸同源。 */
    private static final int CELL_WIDTH = 8;
    private static final int CELL_HEIGHT = 4;

    /**
     * 声明：seed 与层高。
     *
     * <p>
     * period 必须是 cell 高的正整数倍。插值器的角点落在 cell 边界上，层边界若落在 cell 中间，那个 cell
     * 的八个角会分属两层，接缝处按同一格插值两个岛的密度，出现一条四格厚的混合带。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            VanillaFamilySeed.seedParam(),
            SystemParamSpec.ofInt("period").define(1024)
                    .check(value -> value > 0 && value % CELL_HEIGHT == 0,
                            value -> Component.translatable(
                                    "createWorld.tab.infs-farlands.param.error.cell_aligned",
                                    "period", CELL_HEIGHT, value))
                    .build());

    /** 每层链的常数，与 theEnd 的 vanilla 链同源。 */
    private static final double XZ_SCALE = 0.25;
    private static final double Y_SCALE = 0.25;
    private static final double XZ_FACTOR = 80.0;
    private static final double Y_FACTOR = 160.0;
    private static final double SMEAR_SCALE = 4.0;
    private static final double TOP_TARGET = -23.4375;
    private static final double BOTTOM_TARGET = -0.234375;
    private static final double POST_PROCESS_FACTOR = 0.64;

    /** 整条 finalDensity，构造期建好，之后跨线程只读。 */
    private final DensityFunction finalDensity;

    public LayersNoiseSystem(SystemArgs args) {
        long seed = args.getLong("seed");
        int period = args.getInt("period");
        LayerDensity layered = new LayerDensity(seed, period, LayersNoiseSystem::chain);
        this.finalDensity = DensityFunctions.mul(DensityFunctions.interpolated(layered),
                DensityFunctions.constant(POST_PROCESS_FACTOR)).squeeze();
    }

    /** 形参不用：本系统按末地的数据自建，不读宿主路由。签名随接口保留。 */
    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return this.finalDensity;
    }

    @Override
    public int[] noiseSize() {
        return new int[] { CELL_WIDTH / 4, CELL_HEIGHT / 4 };
    }

    /** 末地的 default_block：密度为正处的落点。 */
    @Override
    public BlockState defaultBlock(NoiseGeneratorSettings settings) {
        return Blocks.END_STONE.defaultBlockState();
    }

    /** 末地的 default_fluid 是空气，液面取 MIN_VALUE 后恒出空气。 */
    @Override
    public Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        return (x, y, z) -> new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
    }

    /** 末地不跑 aquifer。密度为正返回 null 才落回 default_block，即末地石。 */
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

    /** 一层的末地链：与 theEnd 的 vanilla 链同式，只换种子。 */
    private static DensityFunction chain(long layerSeed) {
        DensityFunction islands = DensityFunctions.endIslands(layerSeed);
        DensityFunction sloped = DensityFunctions.add(islands, blendedNoise(layerSeed));
        // 顶部在本地 y=56 开始渐消，到 312 压到负数；底部在 4 到 32 之间抬回负目标值。
        DensityFunction top = DensityFunctions.yClampedGradient(56, 312, 1.0, 0.0);
        DensityFunction bottom = DensityFunctions.yClampedGradient(4, 32, 0.0, 1.0);
        DensityFunction slide = DensityFunctions.lerp(bottom, BOTTOM_TARGET,
                DensityFunctions.lerp(top, TOP_TARGET, sloped));
        return DensityFunctions.blendDensity(slide);
    }

    /** 一层的 base_3d_noise，随机源取该层的种子。 */
    private static BlendedNoise blendedNoise(long layerSeed) {
        return BlendedNoise.createUnseeded(XZ_SCALE, Y_SCALE, XZ_FACTOR, Y_FACTOR, SMEAR_SCALE)
                .withNewRandom(new LegacyRandomSource(layerSeed));
    }
}
