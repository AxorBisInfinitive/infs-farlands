package com.inf.farlands.terrain.system.terrain.noise.theEnd.Vanilla;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

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
 * 末地 vanilla 噪声系统：整条 finalDensity 按 26.1.2 的末地数据自建，与所在维度无关。
 *
 * <p>
 * 自建的理由是维度无关。沿用宿主 router 的写法永远是「宿主维度的密度链换个种子」，把这套系统选到别的
 * 维度得到的不是末地噪声。节点与常数来自 {@code NoiseRouterData.end} 与 {@code end.json} 两处，逐项
 * 对应：滑动区间的 56 与 312 来自 {@code slideEndLike(caves, 0, 128)} 的 128-72 与 128+184，4 与 32
 * 来自 bottomStartY 与 bottomEndY，目标值 -23.4375 与 -0.234375 同源。
 *
 * <p>
 * 末地的噪声设置是 {@code legacy_random_source: true}，所以随机流走 legacy 那一支：{@code BlendedNoise}
 * 换成 {@code new LegacyRandomSource(seed)}，与 {@code RandomState} 的 legacy 分支同形。走 xoroshiro
 * 时同一个 seed 会重建出与 vanilla 不同的 {@code end/base_3d_noise}，而地形看上去仍然是地形。
 *
 * <p>
 * 自建只接管密度。{@code default_block}、流体、aquifer、cell 网格与 surface rule 仍来自宿主维度的
 * generator，所以这套系统装到主世界得到的是「末地密度加主世界的方块与网格」，cell 尺寸不同，形状也
 * 因此不同。
 *
 * <p>
 * 树在构造期建一次并共享：vanilla 也把 {@code settings} 里那棵树共享给所有 {@code NoiseChunk}，每次
 * 构造各自套一层 wrap。岛函数与 {@code BlendedNoise} 都是无状态的。
 */
public final class VanillaNoiseSystem implements NoiseSystem {

    /** 声明：seed 用五族共用的空框取值源，见 {@link VanillaFamilySeed}。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    /**
     * 末地 {@code base_3d_noise} 的五个参数。与 {@code NoiseRouterData} 里 {@code BASE_3D_NOISE_END}
     * 的注册值逐字相同，也等于 {@code end.json} 的 noise 尺寸换算结果，所以直接落常量。
     */
    private static final double XZ_SCALE = 0.25;
    private static final double Y_SCALE = 0.25;
    private static final double XZ_FACTOR = 80.0;
    private static final double Y_FACTOR = 160.0;
    private static final double SMEAR_SCALE = 4.0;

    /** 上下两端滑动后的目标值。顶部把密度压到负，底部同样压负，两端就成了空气。 */
    private static final double TOP_TARGET = -23.4375;
    private static final double BOTTOM_TARGET = -0.234375;

    /** postProcess 的乘数。 */
    private static final double POST_PROCESS_FACTOR = 0.64;

    /** 整条 finalDensity，构造期建好，之后跨线程只读。 */
    private final DensityFunction finalDensity;

    public VanillaNoiseSystem(SystemArgs args) {
        this.finalDensity = buildFinalDensity(args.getLong("seed"));
    }

    /** 形参不用：本系统按末地的数据自建，不读宿主路由。签名随接口保留。 */
    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return this.finalDensity;
    }

    /**
     * 末地自己的 router：除密度与 erosion 外全是零值，与 {@code NoiseRouterData.end} 逐项对应。
     *
     * <p>
     * 不能沿用宿主 router 的字段。末地的密度是自建的岛密度，宿主的 {@code preliminarySurfaceLevel} 与矿脉门
     * 与它无关；宿主维度若开着 aquifer，含水层会拿宿主的表面去封一份末地密度的水，那正是这条缺陷的形状。
     *
     * <p>
     * erosion 取 {@code endIslands(0L)}，与 vanilla 的末地 router 同值。本 port 的末地不走含水层，深暗判定读
     * 不到这一项，照抄只为与 vanilla 同形。
     */
    @Override
    public NoiseRouter createRouter(NoiseRouter vanilla) {
        DensityFunction zero = DensityFunctions.zero();
        return new NoiseRouter(
                zero, zero, zero, zero,
                zero, zero, zero,
                DensityFunctions.endIslands(0L),
                zero, zero,
                zero,
                this.finalDensity,
                zero, zero, zero);
    }

    /**
     * 末地的 cell 网格：{@code size_horizontal 2} 即 8 格宽，{@code size_vertical 1} 即 4 格高，与
     * {@code NoiseSettings.END_NOISE_SETTINGS} 同值。
     *
     * <p>
     * 不能返回 null。null 表示用宿主维度的尺寸，那样同一段密度函数会按宿主的网格采样，主世界的 4 宽 8 高
     * 与末地的 8 宽 4 高给出不同的形状，系统就又随维度走了。
     */
    @Override
    public int[] noiseSize() {
        return new int[] { 2, 1 };
    }

    /** 末地的 default_block：密度为正处的落点，不取宿主维度的默认方块。 */
    @Override
    public BlockState defaultBlock(NoiseGeneratorSettings settings) {
        return Blocks.END_STONE.defaultBlockState();
    }

    /**
     * 末地的 default_fluid 是空气。液面取 {@link Integer#MIN_VALUE} 后 {@code FluidStatus.at(y)} 恒出空气，
     * 相邻那一层岩浆也就没有了：那是禁用 aquifer 落回默认 picker 的副产物，不是末地的数据。
     */
    @Override
    public Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        return (x, y, z) -> new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
    }

    /**
     * 末地不跑 aquifer：它的噪声设置里 {@code aquifers_enabled: false}。
     *
     * <p>
     * 宿主维度若开着 aquifer，{@code NoiseChunk} 会走 {@code Aquifer.create} 并被重定向到这里。返回 null
     * 就退回 vanilla 的 {@code NoiseBasedAquifer}，那会为每一个非正方块跑一遍 3D 网格采样加
     * {@code preliminarySurfaceLevel} 查询，起点随机还要取 {@code randomState.aquiferRandom()}。结果仍然是
     * 空气，因为 picker 恒出空气，所以那一整圈只是白算。
     *
     * <p>
     * 密度为正返回 null，这一点与本仓库几个全空气系统不同：那一步要落回 default_block，即末地石。全空气
     * 系统恒返 AIR 是为了让 MaterialRuleList 短路，末地不能短路，否则整片没有石头。
     */
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

    /** 末地的 final_density，算式与 {@code end.json} 的扁平化写法逐层对应。 */
    private static DensityFunction buildFinalDensity(long seed) {
        DensityFunction islands = DensityFunctions.endIslands(seed);
        DensityFunction sloped = DensityFunctions.add(islands, blendedNoise(seed));
        // 顶部在 y=56 处开始渐消，到 y=312 压到负数；底部在 y=4 到 y=32 之间抬回负目标值。
        DensityFunction top = DensityFunctions.yClampedGradient(56, 312, 1.0, 0.0);
        DensityFunction bottom = DensityFunctions.yClampedGradient(4, 32, 0.0, 1.0);
        DensityFunction slide = DensityFunctions.lerp(bottom, BOTTOM_TARGET,
                DensityFunctions.lerp(top, TOP_TARGET, sloped));
        // interpolated 必须包住 blend_density，位置挪一格就改变 NoiseChunk 建插值器的范围。
        return DensityFunctions.mul(DensityFunctions.interpolated(DensityFunctions.blendDensity(slide)),
                DensityFunctions.constant(POST_PROCESS_FACTOR)).squeeze();
    }

    /** 末地的 base_3d_noise，随机源取本系统的 seed 派生。 */
    private static BlendedNoise blendedNoise(long seed) {
        return BlendedNoise.createUnseeded(XZ_SCALE, Y_SCALE, XZ_FACTOR, Y_FACTOR, SMEAR_SCALE)
                .withNewRandom(new LegacyRandomSource(seed));
    }
}
