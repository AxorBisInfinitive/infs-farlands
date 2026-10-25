package com.inf.farlands.terrain.system.terrain.noise.theNether.Vanilla;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 主世界 vanilla 噪声系统：finalDensity 的节点结构原样保留，其中的噪声用构造时传入的 seed 重建。
 *
 * <p>重建走公开 API，不碰任何私有字段：seed 派生 PositionalRandomFactory，再按噪声名取随机源，
 * 与 RandomState 接线 vanilla 噪声的方式一致。重建后的噪声归本类所有，后续修改不必再依赖原实例。
 */
public final class VanillaNoiseSystem implements NoiseSystem {

    /**
     * 声明：seed 用五族共用的空框取值源，见 {@link VanillaFamilySeed}。本系统的 seed 驱动整条 router
     * 的噪声重建，含水层位置与矿脉起点那两处 {@code PositionalRandomFactory} 仍取该 level 的 RandomState。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    /** BlendedNoise 随机源的哈希名。 */
    private static final Identifier TERRAIN = Identifier.withDefaultNamespace("terrain");

    private final long seed;

    /** seed 派生的按名取随机源工厂，构造期建好，之后跨线程只读。 */
    private final PositionalRandomFactory root;

    /** 重建后的 NormalNoise，按噪声数据缓存，同一 key 只建一次。 */
    private final Map<Holder<NormalNoise.NoiseParameters>, NormalNoise> noises = new ConcurrentHashMap<>();

    /** 重建后的主 3D 噪声。withNewRandom 会重建三个 PerlinNoise，不能每个 NoiseChunk 都做。 */
    private volatile BlendedNoise cachedBlendedNoise;

    public VanillaNoiseSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
        this.root = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(this.seed).forkPositional();
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return router.finalDensity().mapAll(this.rebuildVisitor());
    }

    /**
     * 整条 router 按本系统的 seed 重建，换掉的字段因此不再跟着宿主 seed 走。
     *
     * <p>
     * 只重建 finalDensity 不够：含水层的液面封顶值取自 {@code preliminarySurfaceLevel}，而那个函数本身由
     * offset 与 factor 两条噪声样条驱动。密度用族 seed、表面用宿主 seed 时，含水层会拿一个与地形无关的表面
     * 去封液面，深海因此干涸，水也会出现在地形之上。矿脉门与深暗判定同属这一类，一并按族 seed 重建。
     *
     * <p>
     * 地形形状不变：换的只是噪声实例，密度那棵树的结构逐字保留。
     */
    @Override
    public NoiseRouter createRouter(NoiseRouter vanilla) {
        return vanilla.mapAll(this.rebuildVisitor());
    }

    /** 按本系统的 seed 重建噪声的访问器，router 与 finalDensity 两处共用同一份，噪声实例按参数表缓存。 */
    private DensityFunction.Visitor rebuildVisitor() {
        return new DensityFunction.Visitor() {

            @Override
            public DensityFunction apply(DensityFunction function) {
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
        };
    }

    /** 复用原实例自己的缩放参数、只换随机源，参数因此不写死。 */
    private BlendedNoise blendedNoiseFromSeed(BlendedNoise original) {
        BlendedNoise cached = this.cachedBlendedNoise;
        if (cached == null) {
            cached = original.withNewRandom(root.fromHashOf(TERRAIN));
            this.cachedBlendedNoise = cached;
        }
        return cached;
    }
}
