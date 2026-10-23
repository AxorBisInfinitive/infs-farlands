package com.inf.farlands.terrain.biomeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.datafixers.util.Pair;

import com.inf.farlands.mixin.terrain.biome.MultiNoiseBiomeSourceInvoker;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * 按生成器实例登记换过的群系源，并把森林群系的条目改指樱花树林。
 *
 * <p>
 * 为什么按实例登记：{@code ChunkGenerator.getBiomeSource()} 没有 level，只有生成器自己，而「要不要换」
 * 由该 level 选中的群系系统决定。所以安装点拿着 level 算一次结果，getter 只做一次查表。
 *
 * <p>
 * 为什么不改 {@code ChunkGenerator.biomeSource} 字段：那个字段被
 * {@code NoiseBasedChunkGenerator.CODEC} 的 {@code forGetter} 直接读，改它等于让序列化看到一份由数据包
 * 内容算出来的源，而那份源不是预设引用。getter 换出不进任何序列化路径。
 *
 * <p>
 * 并发：安装在主线程的 level 构造末尾，查表发生在 genPool 与主线程两侧，所以表用并发容器；查不到即原样
 * 返回字段值，因此客户端与未安装的维度行为不变。
 */
public final class BiomeSourceFilter {

    private static final Map<ChunkGenerator, BiomeSource> ADAPTED = new ConcurrentHashMap<>();

    private BiomeSourceFilter() {
    }

    /** 登记该生成器换过的源。主线程，level 构造末尾。 */
    public static void register(ChunkGenerator generator, BiomeSource adapted) {
        ADAPTED.put(generator, adapted);
    }

    /** 该生成器换过的源；未登记则原样返回入参。热路径，只读。 */
    public static BiomeSource lookup(ChunkGenerator generator, BiomeSource raw) {
        BiomeSource adapted = ADAPTED.get(generator);
        return adapted != null ? adapted : raw;
    }

    /** 停服时清空。生成器实例随 level 消亡，不清会让下一个世界的查表命中旧源。 */
    public static void clearWorldState() {
        ADAPTED.clear();
    }

    /**
     * 把该源参数表里森林群系的条目改指樱花树林，返回新源；不适用时返回 null。
     *
     * <p>
     * 改的是条目的第二项，第一项即六轴区间与 offset 原样保留。参数表的 RTree 只按第一项建，所以任一气候
     * 目标点选中的仍是同一个条目，fitness 逐项不变，只是那个条目现在指向樱花。这一条让接管是精确的：
     * 樱花占的正是原来那些森林群系占的气候盒子。
     *
     * <p>
     * 返回 null 的两种情形都是「无需改动」，不是失败：源不是多噪声源，例如末地的
     * {@code TheEndBiomeSource}；表里一条森林都没有，例如下界。
     *
     * <p>
     * 判据取 vanilla 自己的标签，不写死名单：数据包扩这些标签时行为自动跟随。三个标签是
     * {@code minecraft:is_forest}、{@code is_taiga}、{@code is_jungle}；另有四个不属任何森林标签、
     * 名字也表达不出来的键单列，即风袭森林、红树林沼泽、疏林恶地与沼泽，它们都是树多的群系。
     */
    public static BiomeSource handForestsToSakura(ServerLevel level, BiomeSource raw) {
        if (!(raw instanceof MultiNoiseBiomeSource source)) {
            return null;
        }
        var biomeLookup = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> sakura = biomeLookup.getOrThrow(Biomes.CHERRY_GROVE);
        // 标签未绑定时 Holder.is 会抛 "Tags not bound"，消息不指认是谁。先用一个已知成员反证一次，
        // 把「标签可用」这条前提变成可取数的判据。
        Holder<Biome> probe = biomeLookup.getOrThrow(Biomes.FOREST);
        if (!probe.is(BiomeTags.IS_FOREST)) {
            throw new IllegalStateException(
                    "farlands: biome tag minecraft:is_forest is not bound, forests cannot be classified");
        }
        Climate.ParameterList<Holder<Biome>> parameters =
                ((MultiNoiseBiomeSourceInvoker) source).farlands$parameters();
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = parameters.values();
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> handed = new ArrayList<>(entries.size());
        int handedOver = 0;
        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : entries) {
            if (isTakenOver(entry.getSecond())) {
                handed.add(Pair.of(entry.getFirst(), sakura));
                handedOver++;
                continue;
            }
            handed.add(entry);
        }
        if (handedOver == 0) {
            return null;
        }
        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(handed));
    }

    /**
     * 该群系是否由樱花树林接管。樱花树林自身不受接管。
     *
     * <p>
     * 三个森林族标签之外，另单列四个键：风袭森林在原版属于 {@code is_hill}，红树林沼泽与沼泽不在任何
     * 森林标签里，疏林恶地属于 {@code is_badlands}。它们的共同点是树多，而前两类名义上也常被当成森林，
     * 所以与标签一起接管。
     */
    private static boolean isTakenOver(Holder<Biome> biome) {
        if (biome.is(Biomes.CHERRY_GROVE)) {
            return false;
        }
        return biome.is(BiomeTags.IS_FOREST)
                || biome.is(BiomeTags.IS_TAIGA)
                || biome.is(BiomeTags.IS_JUNGLE)
                || biome.is(Biomes.WINDSWEPT_FOREST)
                || biome.is(Biomes.MANGROVE_SWAMP)
                || biome.is(Biomes.WOODED_BADLANDS)
                || biome.is(Biomes.SWAMP);
    }
}
