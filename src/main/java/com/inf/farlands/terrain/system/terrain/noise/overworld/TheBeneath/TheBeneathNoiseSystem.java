package com.inf.farlands.terrain.system.terrain.noise.overworld.TheBeneath;

import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.system.common.overworld.TheBeneath.TheBeneathDensity;

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
 * 虚空之下的噪声系统：把数据包的地形密度链整条搬过来。
 *
 * <p>复刻范围是数据包里与噪声有关的那部分：七个密度函数文件组出的 {@code final_density}，噪声设置的
 * 网格尺寸与默认方块，以及关掉水层与矿脉的那两条开关。流体不在其中：本系统不产出任何流体，密度非正处直接
 * 是空气。数据包的维度类型、群系、世界预设、时间线与时钟不在本系统的接口面内，不在此复刻；地表、雕刻、
 * 装饰三族仍由各自的系统决定，选 VOID 才是纯净的深板岩地形。
 *
 * <p>噪声实例由本类按 seed 派生，不取世界的那一份，所以同一份配置在任何世界、任何维度都产出同一批
 * 噪声。seed 填成世界种子、且宿主维度的随机源不是 legacy 时，噪声与世界那两份逐位相同。
 *
 * <p>密度链在构造期建好并缓存：{@code createRouter} 每个窗口 section 与每次
 * {@code createDimensionNoiseChunk} 都会被调一次，重造一棵树是白花。
 */
public final class TheBeneathNoiseSystem implements NoiseSystem {

    /** 声明：只有一个种子，空框由本模组的随机取值器填，进页时重抽。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofLong("seed")
                    .keyword("", () -> Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                            "createWorld.tab.infs-farlands.param.seed.random")
                    .build());

    /**
     * 恒出空气的流体档：本系统不要任何流体。
     *
     * <p>液面取 {@code Integer.MIN_VALUE}，而 {@code FluidStatus.at} 的判据是 y 小于液面才给流体，y 不可能
     * 小于 int 下界，所以任何坐标都落回空气。
     */
    private static final Aquifer.FluidStatus AIR = new Aquifer.FluidStatus(Integer.MIN_VALUE,
            Blocks.AIR.defaultBlockState());

    private static final Aquifer.FluidPicker FLUID_PICKER = (x, y, z) -> AIR;

    private final DensityFunction finalDensity;

    public TheBeneathNoiseSystem(SystemArgs args) {
        this.finalDensity = TheBeneathDensity.build(args.getLong("seed"));
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return this.finalDensity;
    }

    /**
     * 整条 router 只有 final_density 是这条链。数据包里其余十四个字段全是常量 0，所以这里也全是 0：
     * 照抄默认实现只换 final_density 会把宿主维度的气候、矿脉门与地表预扫描一起混进来。
     *
     * <p>三处 0 有各自的后果。{@code vein_toggle} 为 0 时矿脉规则惰性，判据是绝对值的下界 0.4，零加
     * 边缘余量恒小于它；{@code preliminary_surface_level} 为 0 使结构 Y 锚落在 0 附近，与数据包一致；
     * 气候六通道为 0 只影响本 router 的采样器，群系仍由群系族的系统决定。
     */
    @Override
    public NoiseRouter createRouter(NoiseRouter vanilla) {
        DensityFunction zero = DensityFunctions.zero();
        return new NoiseRouter(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
                this.finalDensity, zero, zero, zero);
    }

    /**
     * 数据包的 cell 网格是水平 1、竖直 2，换算成方块即 4 宽 8 高。必须显式给出，返回 null 会跟宿主维度
     * 的网格走，末地是 2 与 1，同一段密度会按另一套网格采样。
     */
    @Override
    public int[] noiseSize() {
        return new int[] { 1, 2 };
    }

    /** 数据包的默认方块是深板岩，密度为正处的落点。 */
    @Override
    public BlockState defaultBlock(NoiseGeneratorSettings settings) {
        return Blocks.DEEPSLATE.defaultBlockState();
    }

    @Override
    public Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        return FLUID_PICKER;
    }

    /**
     * 数据包的 {@code aquifers_enabled} 是 false，对应 {@code Aquifer.createDisabled}：密度为正返回 null，
     * 落回默认方块；非正才问流体。
     *
     * <p>用本类自己的 picker 而不是形参那一个：形参来自构造点，地表与雕刻那条路径把它换成宿主维度的
     * 默认 picker，那个 picker 在 -54 以下给岩浆、63 以下给水，本系统两种都不要。
     */
    @Override
    public Aquifer createAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        return Aquifer.createDisabled(FLUID_PICKER);
    }
}
