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
     * 声明：seed 用五族共用的空框取值源，见 {@link VanillaFamilySeed}。本系统的 seed 只驱动密度
     * 节点重建，aquifer 与矿脉的随机源仍取该 level 的 RandomState。
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
        return router.finalDensity().mapAll(new DensityFunction.Visitor() {

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
        });
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
