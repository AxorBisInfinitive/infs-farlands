package com.inf.farlands.terrain.system.surface.theNether.Vanilla;

import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilyRandom;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;
import com.inf.farlands.terrain.terrainFiller.AbstractTerrainFiller;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

/**
 * vanilla 主世界地表系统：settings.surfaceRule() 的草皮/泥土/石头分层等，随机性取本系统的 seed。
 *
 * <p>
 * 依赖四样东西。vanilla SurfaceSystem，其全高适配由 SurfaceSystemMixin 的 @Overwrite 提供，
 * 列扫描下界到 maxCapIter；维度全高 NoiseChunk，其 preliminarySurfaceLevel 在极端 Y 列约等于
 * vanilla 地表，abovePreliminarySurface 恒 true 自动放行；自定义 NoiseBiomeSource，直查本 port
 * section 的 4×4×4 biome 网格，绕开 @Overwrite getNoiseBiome 的维度 clamp，极端 Y 才正确；
 * BiomeManager.obfuscateSeed(本系统 seed)，复刻 vanilla WorldGenRegion fiddle 语义。
 *
 * <p>
 * surfaceSystem 与 buildSurface 的首参必须来自同一份 RandomState：SurfaceSystem 的噪声在构造时
 * 就从它取定，混用两份即等于两个种子。preliminarySurfaceLevel 不在这里取，它跟地形族的 router 走。
 *
 * <p>
 * 幂等：surfaceRule.tryApply 只替换 defaultBlock 即 stone，已替换的草皮不会重替换，多段 fill
 * 后重复跑无害。NoiseChunk 经 chunk.getOrCreateNoiseChunk 缓存，走 vanilla 字段，首次构造后复用。
 * 跑在 genPool 线程，即 GenTask.execute 内；构造期的 TerrainSystemContext 由
 * createDimensionNoiseChunk 自己收口，这里不再管。
 *
 * <p>
 * 无状态：全部局部构造，实例随 level 走、可跨线程共享。
 */
public final class VanillaSurfaceSystem implements SurfaceSystem {

    /** 声明：seed 由五族共用，见 {@link VanillaFamilySeed}。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    private final long seed;

    public VanillaSurfaceSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
    }

    @Override
    public void applySurface(ServerLevel level, ChunkAccess chunk) {
        // 本系统只服务噪声生成器的维度。维度泛化后任何数据包或模组都能加维度，生成器类型不再只有
        // NoiseBasedChunkGenerator；非噪声维度即使被选中这套也不该跑，直接返回，免得下游裸转抛异常。
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen)) {
            return;
        }
        RandomState random = VanillaFamilyRandom.forSeed(level, this.seed, gen);
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        // 维度全高 NoiseChunk，经 getOrCreateNoiseChunk 缓存到 vanilla 字段。SURFACE 只用
        // preliminarySurfaceLevel，但构造本身会走 NoiseChunkMixin 那两处 @Redirect
        NoiseChunk nc = chunk.getOrCreateNoiseChunk(
                p -> AbstractTerrainFiller.createDimensionNoiseChunk(level, chunk));
        Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        // 自定义 source 直查 section 的 4×4×4 biome 网格，绕开 clamp；seed 用
        // obfuscateSeed(本系统 seed) 复刻 vanilla WorldGenRegion fiddle 语义
        BiomeManager biomeManager = new BiomeManager(
                (qx, qy, qz) -> biomeAt(chunk, biomes, qx, qy, qz),
                BiomeManager.obfuscateSeed(this.seed));
        WorldGenerationContext context = new WorldGenerationContext(gen, chunk);
        random.surfaceSystem().buildSurface(
                random, biomeManager, biomes, settings.useLegacyRandomSource(),
                context, chunk, nc, settings.surfaceRule());
    }

    /** 直查 section biome 网格，quart 局部索引取 &3；section 不存在时 the_void 兜底。 */
    private static Holder<Biome> biomeAt(ChunkAccess chunk, Registry<Biome> biomes, int qx, int qy, int qz) {
        LevelChunkSection s = ((WindowedChunk) chunk).windowedAllSections().get(qy >> 2);
        return s == null ? biomes.getOrThrow(Biomes.THE_VOID)
                : s.getNoiseBiome(qx & 3, qy & 3, qz & 3);
    }
}
