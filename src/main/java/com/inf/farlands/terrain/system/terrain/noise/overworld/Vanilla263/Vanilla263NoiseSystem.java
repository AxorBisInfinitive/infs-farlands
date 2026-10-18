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
     * 声明：关闭坐标折叠的开关与随机种子。
     *
     * <p>{@code enableFarlands} 默认开，对应 26.3 的 {@code DEBUG_ENABLE_FARLANDS}。但它比那个调试开关
     * 影响更远：开时 {@code wrap} 原样返回坐标，边境之地落在 32 位边界上。
     *
     * <p>种子与 Vanilla 族的其余系统共用同一个空框取值源，同一次创建里几族拿到同一个值。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofBoolean("enableFarlands").define(true).build(),
            SystemParamSpec.ofBoolean("topFadeEnabled").define(false).build(),
            VanillaFamilySeed.seedParam());

    private final boolean enableFarlands;
    private final boolean topFadeEnabled;
    private final long seed;

    /** 按 vanilla 路由身份缓存的树。NoiseChunk 每段构造一次，不缓存就会每段重造一棵。 */
    private volatile NoiseRouter cachedVanilla;
    private volatile FloatNode263 cachedTree;

    public Vanilla263NoiseSystem(SystemArgs args) {
        this.enableFarlands = args.getBoolean("enableFarlands");
        this.topFadeEnabled = args.getBoolean("topFadeEnabled");
        this.seed = args.getLong("seed");
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        if (this.cachedVanilla == router) {
            FloatNode263 cached = this.cachedTree;
            if (cached != null) {
                return cached;
            }
        }
        PositionalRandomFactory noises = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(this.seed)
                .forkPositional();
        FloatNode263 tree = OverworldDensity263.build(noises, this.enableFarlands, this.topFadeEnabled);
        this.cachedTree = tree;
        this.cachedVanilla = router;
        return tree;
    }

    /** 用维度噪声设置里的 cell 尺寸，B 这一层不自定义网格。 */
    @Override
    public int[] noiseSize() {
        return null;
    }
}
