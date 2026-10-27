package com.inf.farlands.terrain.structure;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 一个维度的结构基座：群系源、噪声设置、生成器与结构状态，全按调用方给的 seed 建。
 *
 * <p>
 * 存在的理由是维度无关。沿用宿主 level 的 {@code getGeneratorState()} 时，结构集由宿主的群系源过滤：
 * {@code ChunkGeneratorStructureState.createForNormal} 里 {@code hasBiomesForStructureSet} 会把与群系源
 * 无交集的结构集整条去掉，于是主世界那批结构在下界根本进不了列表。自带群系源之后，结构集、XZ 布局与
 * Y 锚都只由本基座与 seed 决定。
 *
 * <p>
 * 生成器只用它的 {@code biomeSource} 与 {@code getBaseHeight}：前者决定生物群系谓词，后者决定 Y 锚。
 * 后者内部构造的 {@code NoiseChunk} 用的是这里的 settings 与 {@link RandomState}，所以调用方必须在
 * {@code TerrainSystemContext.setVanilla(level)} 里跑，否则宿主的地形系统会顶掉这条链。
 *
 * <p>
 * 结构状态的环位置是惰性算的，首次被 {@code isStructureChunk} 问到时会在调用线程上 join，见
 * {@code ChunkGeneratorStructureState.getRingPositionsFor}。所以第一次进维度会有一次等待。
 */
public record StructureBasis(ResourceKey<Level> dimension, ChunkGenerator generator,
        ChunkGeneratorStructureState state) {

    /** 按维度键取该维度的基座。维度不在原版三个之内时抛：本类只服务原版维度的口径。 */
    public static StructureBasis of(ResourceKey<Level> dimension, long seed, RegistryAccess registry) {
        BiomeSource source;
        Holder<NoiseGeneratorSettings> settings;
        if (Level.OVERWORLD.equals(dimension)) {
            source = MultiNoiseBiomeSource.createFromPreset(registry
                    .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        } else if (Level.NETHER.equals(dimension)) {
            source = MultiNoiseBiomeSource.createFromPreset(registry
                    .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
            settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.NETHER);
        } else if (Level.END.equals(dimension)) {
            source = TheEndBiomeSource.create(registry.lookupOrThrow(Registries.BIOME));
            settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.END);
        } else {
            throw new IllegalArgumentException("farlands: no structure basis for " + dimension);
        }
        ChunkGenerator generator = new NoiseBasedChunkGenerator(source, settings);
        RandomState randomState = RandomState.create(settings.value(),
                registry.lookupOrThrow(Registries.NOISE), seed);
        ChunkGeneratorStructureState state = generator.createState(
                registry.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
        return new StructureBasis(dimension, generator, state);
    }
}
