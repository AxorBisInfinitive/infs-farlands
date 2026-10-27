package com.inf.farlands.terrain;

import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 群系系统，决定 section 的 biome 数据来源。
 *
 * 与地形系统正交，biome 是独立阶段，vanilla 的 BIOMES 先于 NOISE。
 * 由 ServerLevel 构造末尾按 id 在 SystemRegistries 里新建，实例随 level 走。
 * 实现必须无状态且纯方法，fillBiomes 跑 genPool 多线程，实例被多个 fill 任务共享。
 *
 * <p>
 * 只有 {@link #layout} 需要覆写，其余全是它的派生：批量填、单点算、缩放种子、{@code BiomeManager} 都从
 * 同一份布局取源与采样器，所以「四处同源」是结构保证，不靠注释约束。不吃群系的系统覆写
 * {@link #computeBiome} 给出常量即可。
 */
public interface BiomeSystem {

    /**
     * 一套群系布局：源、采样器、缩放种子。三者必须同源，所以一次交出，不拆成三个方法分别问。
     *
     * @param source   回答 quart 群系的源。实现里通常就是生成器的 {@code getBiomeSource()}，换过源的系统
     *                 因此自动跟随。
     * @param sampler  该源的采样器。改成别的种子等于换布局。
     * @param zoomSeed {@code BiomeManager} 的缩放种子，决定群系交界在格尺度上的形状
     */
    record BiomeLayout(BiomeSource source, Climate.Sampler sampler, long zoomSeed) {
    }

    /**
     * 本系统的布局。默认是该维度自己的那一套：生成器的群系源、该 level 的采样器、世界种子。
     *
     * <p>
     * 采样器与缩放种子都取该 level 自己的，所以不覆写时行为与「不装本模组」逐字相同；自带种子的族覆写
     * 本方法，把源、采样器与缩放种子一起换掉，三者不会有先后。
     */
    default BiomeLayout layout(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        return new BiomeLayout(gen.getBiomeSource(), level.getChunkSource().randomState().sampler(),
                level.getSeed());
    }

    /**
     * 填 [minSectionY, maxSectionY] 内每个 section 的 4x4x4 biome 网格。布局每个 chunk 取一次。
     *
     * <p>
     * 传给 {@code fillBiomesFromNoise} 的采样器与 resolver 用的是同一份，所以即使某天容器的实现改用被传入的
     * 采样器，答案也不变。
     */
    default void fillBiomes(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY) {
        BiomeLayout biomeLayout = layout(level);
        BiomeResolver resolver = (quartX, quartY, quartZ, sampler) -> biomeLayout.source().getNoiseBiome(
                quartX, quartY, quartZ, biomeLayout.sampler());
        int quartX = QuartPos.fromSection(chunk.getPos().x());
        int quartZ = QuartPos.fromSection(chunk.getPos().z());
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
            section.fillBiomesFromNoise(resolver, biomeLayout.sampler(), quartX, QuartPos.fromSection(sy), quartZ);
        }
    }

    /**
     * 把该维度生成器实际使用的群系源换成本系统要的那一份。默认原样返回。
     *
     * <p>
     * 调一次，落在 ServerLevel 构造末尾；返回值按生成器实例登记，此后该生成器的
     * {@code getBiomeSource()} 一律返回它。所以「只换 section 数据」之外的口径，例如雕刻取群系、
     * 结构判群系、刷怪取群系、{@code /locate biome}，也一并跟随，不需要各自再挂判据。
     *
     * <p>
     * 它不属于 {@link #layout}：本方法跑在 level 构造末尾，那时布局未必可求。换源与布局因此是两件事，
     * 换源的系统两处都要写，例如把森林改指樱花树林的那一份。
     *
     * <p>
     * 默认实现不换源：不吃群系的系统与不装本模组时逐字相同。返回同一实例表示没有改动。
     */
    default BiomeSource adaptBiomeSource(ServerLevel level, BiomeSource raw) {
        return raw;
    }

    /**
     * 该 quart 的群系，单次查询用。surface 与 carver 的取数走 {@link #biomeManager}，不走这里。
     *
     * <p>
     * 先读本系统填进段容器的网格，网格只在段已过 BIOMES 且属于传入的这个 chunk 时可信；其余情形按
     * {@link #computeBiome} 算。生成期两条分支逐值相同，因为网格就是同一份布局填的。唯一不等价的情形是
     * {@code /fillbiome} 之后：网格被改写而计算值不变，那时以网格为准。
     *
     * <p>
     * 每次调用都要取一次布局，所以高频路径不要用它；{@link #biomeManager} 已按 chunk 把布局取好。
     *
     * <p>
     * 当前无调用方：carver 的起点查询走 {@link #computeBiome}，surface 与 carver 的取数走
     * {@link #biomeManager}。留着是给「只要一次答案」的调用点用。
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
     * 直接算一个 quart 的群系，不读段容器。任意 quart 坐标都成立，包括本 chunk 之外的。默认按布局算。
     *
     * <p>
     * 不吃群系的系统覆写本方法给出常量，{@link #fillBiomes} 的默认体也会跟着用那个常量。
     */
    default Holder<Biome> computeBiome(ServerLevel level, int quartX, int quartY, int quartZ) {
        BiomeLayout biomeLayout = layout(level);
        return biomeLayout.source().getNoiseBiome(quartX, quartY, quartZ, biomeLayout.sampler());
    }

    /**
     * 群系缩放种子，默认取布局里的那一个。
     *
     * <p>
     * 它决定 {@code BiomeManager} 八个角里哪个胜出，也就是群系交界在格尺度上的形状。交界形状是群系布局的
     * 一部分，surface 与 carver 只是问，不该改变它。
     */
    default long biomeSeed(ServerLevel level) {
        return layout(level).zoomSeed();
    }

    /**
     * 该 chunk 上取群系用的 {@code BiomeManager}。布局在方法入口取一次，lambda 内复用，逐块查询不再取布局。
     *
     * <p>
     * 缩放种子取本族的那一个，所以 {@code getBiome} 的 ±2 位移与八角投票都落在本族的布局上；投到别的
     * chunk 的角由 {@link #storedBiome} 判空后按布局计算，不会折回本 chunk 的镜像格。
     */
    default BiomeManager biomeManager(ServerLevel level, ChunkAccess chunk) {
        BiomeLayout biomeLayout = layout(level);
        return new BiomeManager((quartX, quartY, quartZ) -> {
            Holder<Biome> stored = storedBiome(chunk, quartX, quartY, quartZ);
            return stored != null ? stored
                    : biomeLayout.source().getNoiseBiome(quartX, quartY, quartZ, biomeLayout.sampler());
        }, BiomeManager.obfuscateSeed(biomeLayout.zoomSeed()));
    }
}
