package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 26.3 主世界密度链的噪声系统。
 *
 * <p>复刻范围是 26.3 的整条 {@code final_density}：噪声叶、密度节点、样条、拓扑全部换成本包内按 26.3
 * 逐行移植的实现，只把最后的 beardifier 留给 26.1.2 的 {@code NoiseChunk} 自己加。
 *
 * <p>逐位一致的可达范围止于本系统 {@code compute} 的返回值。26.1.2 的 {@code NoiseChunk} 之后还要做
 * 插值、加 beardifier、与 aquifer 交互，那些都是 26.1.2 的 double 算术。
 *
 * <p>不支持热配置：参数只在实例化时读一次。
 */
public final class Vanilla263NoiseSystem implements NoiseSystem {

    /**
     * 声明：三个开关与随机种子。
     *
     * <p>{@code enableFarlands} 默认开，对应 26.3 的 {@code DEBUG_ENABLE_FARLANDS}。但它比那个调试开关
     * 影响更远：开时 {@code wrap} 原样返回坐标，边境之地落在 32 位边界上。
     *
     * <p>{@code enableJagged} 默认开，对应 26.3 里那一条锯齿度。关掉时整条锯齿项不挂进树，而不是换成
     * 常量 0：左零短路已按缓冲语义去掉，{@code 0 × NaN} 会照常传播。
     *
     * <p>{@code enableSkyGrid} 默认关，它把标量路径的角点早退接回来，让角点直取内层值、格内插值，
     * 于是角点是无穷的地方出方块、格内是 NaN。26.3 的生成不走那条早退，所以这是研究开关。
     *
     * <p>种子与 Vanilla 族的其余系统共用同一个空框取值源，同一次创建里几族拿到同一个值。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofBoolean("enableFarlands").define(true).build(),
            SystemParamSpec.ofBoolean("topFadeEnabled").define(false).build(),
            SystemParamSpec.ofBoolean("enableJagged").define(true).build(),
            SystemParamSpec.ofBoolean("enableSkyGrid").define(false).build(),
            VanillaFamilySeed.seedParam());

    private final boolean enableFarlands;
    private final boolean topFadeEnabled;
    private final boolean enableJagged;
    private final boolean enableSkyGrid;
    private final long seed;

    /** 本系统 seed 派生的一次性随机源工厂，树与矿脉共用同一份。构造期建好，之后跨线程只读。 */
    private final PositionalRandomFactory noises;

    /**
     * 按 vanilla 路由身份缓存的一整套节点。{@code NoiseChunk} 每段构造一次 {@code createRouter}，不缓存就每段
     * 重造一棵树、四条矿脉噪声。
     *
     * <p>
     * 树与矿脉一起缓存，因为它们只由 seed 与 {@code enableFarlands} 决定，且矿脉要用树里的 {@code y}。
     * 写序是灯标在前、键在后，读者先读键再读灯标，因此不会拿到半个状态。
     */
    private NoiseRouter cachedVanilla;

    private volatile Built cached;

    /** 一次建成的树与矿脉。 */
    private record Built(OverworldDensity263.Tree tree, OreVeins263 veins) {
    }

    public Vanilla263NoiseSystem(SystemArgs args) {
        this.enableFarlands = args.getBoolean("enableFarlands");
        this.topFadeEnabled = args.getBoolean("topFadeEnabled");
        this.enableJagged = args.getBoolean("enableJagged");
        this.enableSkyGrid = args.getBoolean("enableSkyGrid");
        this.seed = args.getLong("seed");
        this.noises = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(this.seed).forkPositional();
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return this.built(router).tree().tree();
    }

    /**
     * 本系统的整条路由：只换本系统自带的那几项，其余照抄宿主。
     *
     * <p>
     * 换掉的三组都有明确的下游。{@code depth} 与 {@code erosion} 被含水层的深暗判定读；{@code preliminarySurfaceLevel}
     * 被含水层的水线与地表规则的 {@code above_preliminary_surface} 读；三条矿脉被 {@code OreVeinifier} 读。
     * 交给它们的是 26.3 自己的树、自己的表面与自己的矿脉噪声，宿主那边的同一批字段因此不再参与。
     *
     * <p>
     * 不换 {@code temperature}、{@code vegetation}、{@code continents}、{@code ridges}：26.3 的树从
     * {@link NoiseData263} 自建这几条，不读路由，而本 port 的群系族另有自己的一份 {@code RandomState}。
     */
    @Override
    public NoiseRouter createRouter(NoiseRouter vanilla) {
        Built built = this.built(vanilla);
        OverworldDensity263.Tree tree = built.tree();
        OreVeins263 veins = built.veins();
        return new NoiseRouter(
                vanilla.barrierNoise(),
                vanilla.fluidLevelFloodednessNoise(),
                vanilla.fluidLevelSpreadNoise(),
                vanilla.lavaNoise(),
                vanilla.temperature(),
                vanilla.vegetation(),
                vanilla.continents(),
                tree.erosion(),
                tree.depth(),
                vanilla.ridges(),
                new PreliminarySurface263(tree.offset(), tree.factor()),
                tree.tree(),
                veins.toggle(),
                veins.ridged(),
                veins.gap());
    }

    /** 该 vanilla 路由对应的那一套节点，按身份缓存，一次构造只建一份。 */
    private Built built(NoiseRouter vanilla) {
        Built current = this.cached;
        if (this.cachedVanilla == vanilla && current != null) {
            return current;
        }
        OverworldDensity263.Tree tree = OverworldDensity263.build(this.noises, this.enableFarlands,
                this.topFadeEnabled, this.enableJagged, this.enableSkyGrid);
        Built fresh = new Built(tree, OreVeins263.of(tree.y(), this.noises, this.enableFarlands));
        this.cached = fresh;
        this.cachedVanilla = vanilla;
        return fresh;
    }
}
