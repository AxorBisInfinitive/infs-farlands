package com.inf.farlands.terrain;

import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 群系系统，决定 section 的 biome 数据来源。
 *
 * 与地形系统正交，biome 是独立阶段，vanilla 的 BIOMES 先于 NOISE。
 * 由 ServerLevel 构造末尾按 id 在 SystemRegistries 里新建，实例随 level 走。
 * 实现必须无状态且纯方法，fillBiomes 跑 genPool 多线程，实例被多个 fill 任务共享。
 */
public interface BiomeSystem {

    /** 填 [minSectionY, maxSectionY] 内每个 section 的 4x4x4 biome 网格。 */
    void fillBiomes(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY);

    /**
     * 把该维度生成器实际使用的群系源换成本系统要的那一份。默认原样返回。
     *
     * <p>
     * 调一次，落在 ServerLevel 构造末尾；返回值按生成器实例登记，此后该生成器的
     * {@code getBiomeSource()} 一律返回它。所以「只换 section 数据」之外的口径，例如雕刻取群系、
     * 结构判群系、刷怪取群系、{@code /locate biome}，也一并跟随，不需要各自再挂判据。
     *
     * <p>
     * 默认实现不换源：不吃群系的系统与不装本模组时逐字相同。返回同一实例表示没有改动。
     */
    default BiomeSource adaptBiomeSource(ServerLevel level, BiomeSource raw) {
        return raw;
    }

    /**
     * 该 quart 的群系。surface 与 carver 两个阶段取数都走这里，不自己拼 source。
     *
     * <p>
     * 先读本系统填进段容器的网格，网格只在段已过 BIOMES 且属于传入的这个 chunk 时可信；其余情形由
     * {@link #computeBiome} 算。生成期两条分支逐值相同，因为网格就是 {@code computeBiome} 那一份采样器
     * 填的。唯一不等价的情形是 {@code /fillbiome} 之后：网格被改写而计算值不变，那时以网格为准。
     *
     * <p>
     * 池线程可调：网格分支只读并发容器，计算分支是纯函数，两者都不碰服务端的 chunk 表。
     */
    default Holder<Biome> biomeAt(ServerLevel level, ChunkAccess chunk, int quartX, int quartY, int quartZ) {
        Holder<Biome> stored = storedBiome(chunk, quartX, quartY, quartZ);
        return stored != null ? stored : computeBiome(level, quartX, quartY, quartZ);
    }

    /**
     * 网格分支：quart 属于该 chunk、那一段已物化、且该段已过 BIOMES 时返回段里的群系，否则 null。
     *
     * <p>
     * 档位那一条不能省：段随时可能被别的路径物化，而 {@code LevelChunkSection} 的 biome 容器默认是工厂
     * 默认群系，只判「段存在」会把那个默认值当成真群系返回，看起来正常却错。档位低于 BIOMES 时交给
     * {@link #computeBiome}，那里不读段容器。
     *
     * <p>
     * 只做整数比较、一次档位查表与一次段容器查询，所以能在池线程上调。
     */
    static Holder<Biome> storedBiome(ChunkAccess chunk, int quartX, int quartY, int quartZ) {
        ChunkPos pos = chunk.getPos();
        if ((quartX >> 2) != pos.x() || (quartZ >> 2) != pos.z()) {
            return null;
        }
        int sectionY = quartY >> 2;
        if (!(chunk instanceof LevelChunk levelChunk)
                || SectionStage.getStage(levelChunk, sectionY) < SectionStage.BIOMES) {
            return null;
        }
        LevelChunkSection section = ((WindowedChunk) chunk).windowedAllSections().get(sectionY);
        return section == null ? null : section.getNoiseBiome(quartX & 3, quartY & 3, quartZ & 3);
    }

    /**
     * 直接算一个 quart 的群系，不读段容器。任意 quart 坐标都成立，包括本 chunk 之外的。
     *
     * <p>
     * 实现必须用本系统填网格时那一份采样器，否则 {@link #biomeAt} 的两条分支会分叉成两份答案。
     */
    Holder<Biome> computeBiome(ServerLevel level, int quartX, int quartY, int quartZ);

    /**
     * 群系缩放种子。{@code BiomeManager} 用它决定八个角里哪个胜出，也就是群系交界在格尺度上的形状。
     *
     * <p>
     * 它归本族：交界形状是群系布局的一部分，surface 与 carver 只是问，不该改变它。没有布局的系统返回
     * 该 level 的世界种子，同一份布局因此在任何配置下得出同一个交界。
     */
    long biomeSeed(ServerLevel level);

    /**
     * 该 chunk 上取群系用的 {@code BiomeManager}。
     *
     * <p>
     * 缩放种子取本族的那一个，所以 {@code getBiome} 的 ±2 位移与八角投票都落在本族的布局上；投到别的
     * chunk 的角由 {@link #biomeAt} 转给 {@link #computeBiome}，不会折回本 chunk 的镜像格。
     */
    default BiomeManager biomeManager(ServerLevel level, ChunkAccess chunk) {
        return new BiomeManager((quartX, quartY, quartZ) -> biomeAt(level, chunk, quartX, quartY, quartZ),
                BiomeManager.obfuscateSeed(biomeSeed(level)));
    }
}
