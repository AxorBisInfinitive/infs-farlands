package com.inf.farlands.terrain.system.carver.theEnd.Vanilla;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.CarvingMaskStorage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilyRandom;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;
import com.inf.farlands.terrain.terrainFiller.AbstractTerrainFiller;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;

/**
 * vanilla 主世界雕刻系统：biome json 的 carver 列表，CAVE/CAVE_EXTRA_UNDERGROUND/CANYON，
 * 加 WorldCarver 体系，目标 chunk 为中心 ±8 chunk 的起点网格，随机性取本系统的 seed。
 *
 * 移植自 vanilla NoiseBasedChunkGenerator.applyCarvers 的 1.21.1 形态：
 * - 以目标 chunk 为中心遍历 17×17 个起点，289 次调用。每个起点用 biomeSource 查 biome，
 *   再取该 biome 的 carver 列表，不依赖起点 chunk 的数据，起点未生成也能查询
 * - setLargeFeatureSeed(本系统 seed + carver序号, 起点cx, 起点cz) 做确定性随机，经 isStartChunk
 *   概率过滤，再 ConfiguredWorldCarver.carve。carvingMask 去重，只雕目标 chunk
 * - CarvingContext 用维度全高 NoiseChunk，其 aquifer 覆盖 carver 带，同 SURFACE 模式；它那条
 *   surface rule 取地表族的那一份，与 surface 阶段同源，不直读维度设置的
 *
 * seed 只驱动起点随机与两处 biome 查询的采样器；NoiseChunk 内部的 aquifer 与矿脉随机源不在这里取，
 * 它们仍来自该 level 的 RandomState。
 *
 * Y 有界：carver 起点 Y 由 biome json 配置的 HeightProvider 给出，锚定在维度带内，极端 Y
 * 天然不会被雕。carve 只替换 config.replaceable 即 stone，替换成什么由 aquifer.computeSubstance
 * 决定，空气或水，y ≤ lavaLevel 时填岩浆。
 *
 * 无状态：全部局部构造 CarvingContext/NoiseChunk/WorldgenRandom，实例随 level 走、可跨线程共享；
 * 构造期的 TerrainSystemContext 由 createDimensionNoiseChunk 自己收口。
 */
public final class VanillaCarverSystem implements CarverSystem {

    /** 声明：seed 由五族共用，见 {@link VanillaFamilySeed}。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    /** 起点网格半径，vanilla applyCarvers 硬编码 8。 */
    private static final int GRID_RADIUS = 8;

    private final long seed;

    public VanillaCarverSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
    }

    @Override
    public void applyCarvers(ServerLevel level, ChunkAccess chunk) {
        // 与 VanillaSurfaceSystem 同规：本系统只服务噪声生成器的维度，非噪声维度直接返回，避免下游裸转。
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen)) {
            return;
        }
        RandomState random = VanillaFamilyRandom.forSeed(level, this.seed, gen);
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        // 维度全高 NoiseChunk，其 aquifer 网格覆盖 carver 带，fill 的窗口段 NoiseChunk 不覆盖
        NoiseChunk nc = chunk.getOrCreateNoiseChunk(
                p -> AbstractTerrainFiller.createDimensionNoiseChunk(level, chunk));
        Aquifer aquifer = nc.aquifer();
        // 表层材料那条规则与 surface 阶段同源：取地表族交付的那一份，不直读维度设置。
        SurfaceRules.RuleSource surfaceRule = ((LevelSystems) level).surfaceSystem().surfaceRule(settings);
        CarvingContext carvingContext = new CarvingContext(
                gen, level.registryAccess(), chunk.getHeightAccessorForGeneration(),
                nc, random, surfaceRule);
        CarvingMask carvingMask = ((CarvingMaskStorage) chunk).getOrCreateCarvingMask();

        // 群系与 surface 阶段同源，都走 biome 族。起点那一问用裸 quart，与 vanilla 相同；
        // carveBlock 的 accessor 用带 ±2 模糊的 manager，于是两处看到的是同一条交界。
        BiomeSystem biomeSystem = ((LevelSystems) level).biomeSystem();
        BiomeManager biomeManager = biomeSystem.biomeManager(level, chunk);
        Function<BlockPos, Holder<Biome>> biomeAccessor = biomeManager::getBiome;

        ChunkPos target = chunk.getPos();
        WorldgenRandom worldgenrandom = new WorldgenRandom(
                new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        for (int j = -GRID_RADIUS; j <= GRID_RADIUS; j++) {
            for (int k = -GRID_RADIUS; k <= GRID_RADIUS; k++) {
                ChunkPos start = new ChunkPos(target.x() + j, target.z() + k);
                // quart 用 fromSection，等于 start*4，而非 fromBlock(getMinBlockX())：
                // getMinBlockX 被本 port 的饱和 @Overwrite 覆盖，边界 chunk 会错位
                Holder<Biome> biome = biomeSystem.computeBiome(level,
                        QuartPos.fromSection(start.x()), 0, QuartPos.fromSection(start.z()));
                // 26.1.2 把 ChunkGenerator.getBiomeGenerationSettings 标了 @Deprecated，而它对
                // NoiseBasedChunkGenerator 的实现就是 biome.value().getGenerationSettings()
                BiomeGenerationSettings biomeGen = biome.value().getGenerationSettings();
                int l = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : biomeGen.getCarvers()) {
                    ConfiguredWorldCarver<?> carver = holder.value();
                    worldgenrandom.setLargeFeatureSeed(this.seed + l, start.x(), start.z());
                    if (carver.isStartChunk(worldgenrandom)) {
                        carver.carve(carvingContext, chunk, biomeAccessor, worldgenrandom,
                                aquifer, start, carvingMask);
                    }
                    l++;
                }
            }
        }
    }
}
