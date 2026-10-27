package com.inf.farlands.terrain.system.biome.overworld.Oct;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctNoiseHarvest;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctNoiseSource;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctOverworldDensity;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctScale;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * 主世界 Oct 群系系统：气候用与 Oct 地形同一套缩放噪声采样，群系布局因此与地形同尺度。
 *
 * <p>vanilla 的群系气候来自 RandomState 的 sampler，那里的密度函数没有缩放。这里按 level 的
 * vanilla router 自建一个用 Oct 缩放链的 Climate.Sampler，链按 router 身份缓存一次。
 * 采集不全时回退 vanilla sampler。
 *
 * <p>
 * 源仍是生成器那一份，换的只是采样器与缩放种子，三者由 {@link #layout} 一次交出。
 */
public final class OctBiomeSystem implements BiomeSystem {

    /** 声明：seed 只有一条映射，左端是空串；三轴缩放默认不缩放。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofLong("seed")
                    .keyword("", () -> Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                            "createWorld.tab.infs-farlands.param.seed.random")
                    .build(),
            SystemParamSpec.ofDouble("scaleX").define(1.0).build(),
            SystemParamSpec.ofDouble("scaleY").define(1.0).build(),
            SystemParamSpec.ofDouble("scaleZ").define(1.0).build());

    private final long seed;
    private final OctScale scale;

    /** 自建 sampler 与其对应的 vanilla router，写序先结果后键，读序先键后结果。 */
    private volatile Climate.Sampler cachedSampler;
    private volatile NoiseRouter cachedVanilla;

    public OctBiomeSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
        this.scale = new OctScale(args.getDouble("scaleX"), args.getDouble("scaleY"), args.getDouble("scaleZ"));
    }

    @Override
    public BiomeLayout layout(ServerLevel level) {
        return new BiomeLayout(level.getChunkSource().getGenerator().getBiomeSource(), sampler(level),
                this.seed);
    }

    /** 自建缩放气候采样器，按 level 的 vanilla router 身份缓存；采集不全回退上层的 sampler。 */
    private Climate.Sampler sampler(ServerLevel level) {
        NoiseRouter vanilla = level.getChunkSource().randomState().router();
        Climate.Sampler cached = this.cachedSampler;
        if (cached != null && this.cachedVanilla == vanilla) {
            return cached;
        }
        PositionalRandomFactory root = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(seed).forkPositional();
        OctNoiseSource source = new OctNoiseSource(scale, root, OctNoiseHarvest.harvest(vanilla));
        OctOverworldDensity density;
        try {
            density = new OctOverworldDensity(scale, source);
        } catch (RuntimeException e) {
            if (source.missing()) {
                return level.getChunkSource().randomState().sampler();
            }
            throw e;
        }
        Climate.Sampler built = new Climate.Sampler(
                density.temperature(),
                density.vegetation(),
                density.continents(),
                density.erosion(),
                density.depth(),
                density.ridges(),
                spawnTarget(level));
        this.cachedSampler = built;
        this.cachedVanilla = vanilla;
        return built;
    }

    private static List<Climate.ParameterPoint> spawnTarget(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen) {
            return gen.generatorSettings().value().spawnTarget();
        }
        return List.of();
    }
}
