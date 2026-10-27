package com.inf.farlands.terrain.system.biome.overworld.Sakura;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.biomeSource.BiomeSourceFilter;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilyRandom;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * 樱花群系系统：vanilla 主世界的多噪声布局，只多一条，森林群系一律由樱花树林接管。
 *
 * <p>
 * 森林的判据是 vanilla 自己的三个标签，即 {@code minecraft:is_forest}、{@code is_taiga} 与
 * {@code is_jungle}，加四个单列的树多群系，即风袭森林、红树林沼泽、疏林恶地与沼泽；不是本类写的名单，
 * 所以数据包改这些标签时行为跟着变。樱花树林自身不受接管，它现在是唯一还能出现的森林类群系。
 *
 * <p>
 * 接管是逐项改指：参数表里属于上面这些群系的条目，第二项换成樱花树林，第一项的六轴区间与 offset 原样
 * 保留。RTree 只按第一项建，所以每个气候目标点选中的仍是同一条目，樱花占的正是原来那些森林群系占的
 * 气候盒子。
 *
 * <p>
 * 换源挂在 {@link #adaptBiomeSource} 上，由 level 构造末尾调一次并按生成器实例登记，因此雕刻取群系、
 * 结构判群系、刷怪取群系与 {@code /locate biome} 都跟随。{@link #layout} 取的是
 * {@code gen.getBiomeSource()}，也就是换过的那份，所以不必再挂第二处判据。
 *
 * <p>
 * 与另三族 vanilla 实现同形：只服务噪声生成器的维度。非噪声生成器的维度既没有噪声设置可建
 * {@code RandomState}，也没有多噪声参数表可换，两条路都退回该 level 自己那份。
 */
public final class SakuraBiomeSystem implements BiomeSystem {

    /** 声明：seed 由五族共用，见 {@link VanillaFamilySeed}。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    private final long seed;

    public SakuraBiomeSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
    }

    /** 把森林群系的条目改指樱花树林；不适用的源原样返回。 */
    @Override
    public BiomeSource adaptBiomeSource(ServerLevel level, BiomeSource raw) {
        BiomeSource adapted = BiomeSourceFilter.handForestsToSakura(level, raw);
        return adapted != null ? adapted : raw;
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
