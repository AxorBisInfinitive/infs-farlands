package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * beta 1.7.3 噪声系统，把 {@link BetaDensityFunction} 注入 finalDensity。
 *
 * <p>参数两项：topFadeEnabled 是地形密度公式的顶部渐消项，默认关；seed 决定噪声实例，默认每次
 * 随机，取值落盘后随世界固定。
 *
 * <p>噪声实例由本系统持有，随 level 走。每次 createFinalDensity 返回新的密度函数，因为密度函数
 * 带 8 角与网格点缓存，禁止共享。
 */
public final class Beta173NoiseSystem implements NoiseSystem {

    /** 声明：顶部渐消默认关；seed 只有一条映射，左端是空串，求值一次得到一个随机 long。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofBoolean("topFadeEnabled").define(false).build(),
            SystemParamSpec.ofLong("seed")
                    .keyword("", () -> Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                            "createWorld.tab.infs-farlands.param.seed.random")
                    .build());

    private final boolean topFadeEnabled;
    private final BetaTerrainNoise noise;

    public Beta173NoiseSystem(SystemArgs args) {
        this.topFadeEnabled = args.getBoolean("topFadeEnabled");
        this.noise = new BetaTerrainNoise(args.getLong("seed"));
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return new BetaDensityFunction(this.noise, this.topFadeEnabled);
    }

    /**
     * 本系统的整条路由：只换表面与深暗，其余照抄宿主。
     *
     * <p>
     * 换 {@code preliminarySurfaceLevel} 是因为含水层的水线、地表规则的 {@code above_preliminary_surface} 与
     * 深暗判定都从这一条取表面。沿用宿主那一份时，密度是 beta 的、表面是宿主地形的，水会按一份与地形无关的
     * 表面灌出来，地表层也按它错位。
     *
     * <p>
     * {@code erosion} 与 {@code depth} 取常量 0：深暗的判据是
     * {@code erosion < -0.225 && depth > 0.9}，beta 没有这两个概念，常量 0 让它永不成立，含水层不会出现与
     * 地形无关的干井。
     *
     * <p>
     * 四条含水层噪声与三条矿脉噪声仍取宿主的：它们是坐标上的独立随机场，不派生自地形形状，取哪一份只决定用
     * 哪个种子。
     *
     * <p>
     * 群系布局在这里取、存进 {@link BetaPreliminarySurface}：本方法跑在 {@code NoiseChunk} 的构造点之内，
     * 那时侧信道是设着的；而 prelim 的 {@code compute} 跑在含水层与地表规则里，那时已经清了。
     */
    @Override
    public NoiseRouter createRouter(NoiseRouter vanilla) {
        ServerLevel level = TerrainSystemContext.get();
        BiomeSystem.BiomeLayout layout = ((LevelSystems) level).biomeSystem().layout(level);
        DensityFunction zero = DensityFunctions.constant(0.0);
        return new NoiseRouter(
                vanilla.barrierNoise(),
                vanilla.fluidLevelFloodednessNoise(),
                vanilla.fluidLevelSpreadNoise(),
                vanilla.lavaNoise(),
                vanilla.temperature(),
                vanilla.vegetation(),
                vanilla.continents(),
                zero,
                zero,
                vanilla.ridges(),
                new BetaPreliminarySurface(this.noise, layout),
                this.createFinalDensity(vanilla),
                vanilla.veinToggle(),
                vanilla.veinRidged(),
                vanilla.veinGap());
    }

    /**
     * 顶部渐消项的取值。构造后不变，供外部按当前系统参数判定。
     */
    public boolean topFadeEnabled() {
        return this.topFadeEnabled;
    }
}
