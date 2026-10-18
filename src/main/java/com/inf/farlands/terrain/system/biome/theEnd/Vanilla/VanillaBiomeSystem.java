package com.inf.farlands.terrain.system.biome.theEnd.Vanilla;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilyRandom;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * vanilla 主世界群系系统：MultiNoiseBiomeSource 原版布局，气候采样器取本系统的 seed。
 *
 * <p>
 * fillBiomes 用维度 generator 的 biomeSource 加该 seed 的 Climate.Sampler，6 通道
 * temperature/vegetation/continents/erosion/depth/ridges，对 fill 段内每 section 填 4×4×4
 * biome 网格，走原版 LevelChunkSection.fillBiomesFromNoise。
 *
 * <p>
 * 换 sampler 就是换布局：MultiNoiseBiomeSource 的取值完全由传入的 sampler 决定，与 biomeSource
 * 自身无关。非噪声生成器的维度没有噪声设置可建 RandomState，退回该 level 自己那份，那种维度的
 * 布局本来也不由气候采样器决定。
 *
 * <p>
 * XZ quart 用 QuartPos.fromSection(chunkX/Z)，等于 chunk*4，而非
 * QuartPos.fromBlock(getMinBlockX())：后者被本 port 的饱和 @Overwrite 覆盖，负极端 -2.14B
 * 饱和到 MIN_VALUE+15，quart 会差 3，即 12 block 布局错位。
 *
 * <p>
 * biomeSource 每次 fill 现拿，防维度或世界切换陈旧；sampler 与 RandomState 同源，纯方法多线程安全。
 */
public final class VanillaBiomeSystem implements BiomeSystem {

    /** 声明：seed 由五族共用，见 {@link VanillaFamilySeed}。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    private final long seed;

    public VanillaBiomeSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
    }

    @Override
    public void fillBiomes(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        Climate.Sampler sampler = sampler(level, gen);
        int qx = QuartPos.fromSection(chunk.getPos().x());
        int qz = QuartPos.fromSection(chunk.getPos().z());
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
            section.fillBiomesFromNoise(gen.getBiomeSource(), sampler, qx, QuartPos.fromSection(sy), qz);
        }
    }

    /** 该 seed 的气候采样器；非噪声生成器的维度退回该 level 自己那份。 */
    private Climate.Sampler sampler(ServerLevel level, ChunkGenerator gen) {
        if (gen instanceof NoiseBasedChunkGenerator noiseGen) {
            return VanillaFamilyRandom.forSeed(level, this.seed, noiseGen).sampler();
        }
        return level.getChunkSource().randomState().sampler();
    }
}
