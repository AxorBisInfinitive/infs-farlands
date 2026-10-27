package com.inf.farlands.terrain.system.biome.theNether.Vanilla;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilyRandom;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * vanilla 下界群系系统：MultiNoiseBiomeSource 原版布局，气候采样器取本系统的 seed。
 *
 * <p>
 * 换 sampler 就是换布局：MultiNoiseBiomeSource 的取值完全由传入的 sampler 决定，与 biomeSource 自身无关。
 * 非噪声生成器的维度没有噪声设置可建 RandomState，采样器退回该 level 自己那份，那种维度的布局本来也不由
 * 气候采样器决定。
 *
 * <p>
 * 源、采样器与缩放种子由 {@link #layout} 一次交出，所以批量填、单点查与交界取整用的是同一颗种子与同一个源。
 *
 * <p>
 * 无状态：布局每次现取，纯方法多线程安全。
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
    public BiomeLayout layout(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        return new BiomeLayout(gen.getBiomeSource(), sampler(level, gen), this.seed);
    }

    /** 该 seed 的气候采样器；非噪声生成器的维度退回该 level 自己那份。 */
    private Climate.Sampler sampler(ServerLevel level, ChunkGenerator gen) {
        if (gen instanceof NoiseBasedChunkGenerator noiseGen) {
            return VanillaFamilyRandom.forSeed(level, this.seed, noiseGen).sampler();
        }
        return level.getChunkSource().randomState().sampler();
    }
}
