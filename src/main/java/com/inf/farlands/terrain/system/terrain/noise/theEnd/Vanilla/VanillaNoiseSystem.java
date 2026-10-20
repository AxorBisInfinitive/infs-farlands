package com.inf.farlands.terrain.system.terrain.noise.theEnd.Vanilla;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 末地 vanilla 噪声系统：finalDensity 的节点结构原样保留，其中的噪声用构造时传入的 seed 重建。
 *
 * <p>
 * 末地的噪声设置是 {@code legacy_random_source: true}，所以重接随机流必须走 {@code RandomState} 的
 * legacy 那一支：{@code BlendedNoise} 换成 {@code new LegacyRandomSource(seed)}，噪声实例用同一个源 fork
 * 出的 positional factory。走 xoroshiro 时同一个 seed 会重建出与 vanilla 不同的
 * {@code end/base_3d_noise}，而地形看上去仍然是地形，从读数上分不出来。
 *
 * <p>
 * 岛形也要一起接过来。vanilla 的 {@code end_islands} 在 {@code RandomState} 构造时被换成那个实例的
 * 种子，而本系统收到的 router 属于 level，种子是世界种子；群系那条走 {@code VanillaFamilyRandom} 建的
 * 另一份 {@code RandomState}，已经是系统种子。只换噪声叶不换岛函数，地形与群系就会落在两个岛形上。所以
 * {@code createFinalDensity} 把岛函数也换成 {@code DensityFunctions.endIslands(seed)}。
 *
 * <p>
 * 重建走公开 API，不碰任何私有字段。重建后的噪声归本类所有，后续修改不必再依赖原实例。
 */
public final class VanillaNoiseSystem implements NoiseSystem {

    /**
     * 声明：seed 用五族共用的空框取值源，见 {@link VanillaFamilySeed}。本系统的 seed 只驱动密度
     * 节点重建，aquifer 与矿脉的随机源仍取该 level 的 RandomState。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    /** 岛函数的探针类。目标类型是 protected static final，跨包写不出类型，只能从实例上取。 */
    private static final Class<?> END_ISLANDS = DensityFunctions.endIslands(0L).getClass();

    private final long seed;

    /** legacy 源 fork 出的按名取随机源工厂，构造期建好，之后跨线程只读。 */
    private final PositionalRandomFactory root;

    /** 重建后的 NormalNoise，按噪声数据缓存，同一 key 只建一次。 */
    private final Map<Holder<NormalNoise.NoiseParameters>, NormalNoise> noises = new ConcurrentHashMap<>();

    /** 重建后的主 3D 噪声。withNewRandom 会重建三个 PerlinNoise，不能每个 NoiseChunk 都做。 */
    private volatile BlendedNoise cachedBlendedNoise;

    /** 按 vanilla 路由身份缓存的树。NoiseChunk 每段构造一次，不缓存就会每段重造一棵。 */
    private volatile NoiseRouter cachedVanilla;
    private volatile DensityFunction cachedTree;

    public VanillaNoiseSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
        this.root = new LegacyRandomSource(this.seed).forkPositional();
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        if (this.cachedVanilla == router) {
            DensityFunction cached = this.cachedTree;
            if (cached != null) {
                return cached;
            }
        }
        DensityFunction built = router.finalDensity().mapAll(new DensityFunction.Visitor() {

            @Override
            public DensityFunction apply(DensityFunction function) {
                // 岛函数与噪声叶一起换：只换噪声叶会让岛形留在世界种子上，与群系分属两个岛形。
                if (END_ISLANDS.isInstance(function)) {
                    return DensityFunctions.endIslands(VanillaNoiseSystem.this.seed);
                }
                return function instanceof BlendedNoise blended ? blendedNoiseFromSeed(blended) : function;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                Holder<NormalNoise.NoiseParameters> data = noise.noiseData();
                NormalNoise rebuilt = noises.computeIfAbsent(data,
                        key -> NormalNoise.create(root.fromHashOf(key.unwrapKey().orElseThrow().identifier()),
                                key.value()));
                return new DensityFunction.NoiseHolder(data, rebuilt);
            }
        });
        // 写序先结果后键，读序先键后结果：读到半个状态只会让下一次重造一遍，不会拿到错的树。
        this.cachedTree = built;
        this.cachedVanilla = router;
        return built;
    }

    /** 复用原实例自己的缩放参数、只换随机源，参数因此不写死。 */
    private BlendedNoise blendedNoiseFromSeed(BlendedNoise original) {
        BlendedNoise cached = this.cachedBlendedNoise;
        if (cached == null) {
            // 与 RandomState 的 legacy 分支同形：那一支传的是 new LegacyRandomSource(seed) 本身，
            // 不是 positional factory 上按名取的随机源。
            cached = original.withNewRandom(new LegacyRandomSource(this.seed));
            this.cachedBlendedNoise = cached;
        }
        return cached;
    }
}
