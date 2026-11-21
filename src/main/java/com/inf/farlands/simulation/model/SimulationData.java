package com.inf.farlands.simulation.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.registry.SystemsData;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 模拟层的系统选择，落盘为 {@code data/infs-farlands/simulation.dat}。
 *
 * <p>
 * 与 {@code systems.dat} 分开：给旧档加必填字段会让读盘直接抛，这是引入新档案时唯一不伤旧世界的做法。结构是维度
 * 到两族的选择，复用 {@link SystemsData.SystemSelection} 与它的参数编码，不复制第二套参数类型。
 *
 * <p>
 * 两族都是必填字段。加族时不给旧档补默认值：缺字段即解析失败，世界列表按「档案读不出」判成不可打开，这是加必填
 * 字段的必然结果，不是缺陷。
 *
 * <p>
 * 选择是唯一来源。生成用的系统与档案里的系统永远同源，因为没有任何路径能在已有世界上改选择。
 */
public final class SimulationData extends SavedData {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "simulation");

    public static final Codec<SimulationData> CODEC = Codec
            .unboundedMap(Identifier.CODEC, LevelSelection.CODEC)
            .xmap(SimulationData::new, SimulationData::levels);

    /**
     * 与 {@code SystemsData} 取同一个 dataFixType：本 port 不走 vanilla 的读路径，该类别实际不被用到，
     * 取一个 remainder 常量是为了即便被碰到也不改写内容。
     */
    public static final SavedDataType<SimulationData> TYPE = new SavedDataType<>(
            ID, SimulationData::new, CODEC, DataFixTypes.SAVED_DATA_WEATHER);

    /** 维度 id 到该维度的两族选择，保插入顺序以便落盘可读。 */
    private final Map<Identifier, LevelSelection> levels;

    /** SavedDataType 要求无参构造器；本 port 不走 computeIfAbsent，这个空对象不会被采用。 */
    public SimulationData() {
        this(Map.of());
    }

    public SimulationData(Map<Identifier, LevelSelection> levels) {
        this.levels = Collections.unmodifiableMap(new LinkedHashMap<>(levels));
    }

    /** 全部维度到其选择，保插入顺序。 */
    public Map<Identifier, LevelSelection> levels() {
        return this.levels;
    }

    /** 该维度的选择，缺失返回 null，由调用方决定失败语义。 */
    public LevelSelection selection(Identifier dimension) {
        return this.levels.get(dimension);
    }

    /**
     * 一个维度的两族选择，族键是 {@code terrain} 与 {@code biome}。两个字段都是必填，缺一即解析失败。
     *
     * <p>
     * {@code terrain} 那一格装的是地形族的选择，而地形族有三种范式：密度链、逐段、逐块，具体哪一种由那个系统自己
     * 实现什么接口决定，不写进档案。所以这个键名指的是族，不是范式。
     */
    public record LevelSelection(SystemsData.SystemSelection terrain, SystemsData.SystemSelection biome) {

        public static final Codec<LevelSelection> CODEC = RecordCodecBuilder.create(i -> i.group(
                SystemsData.SystemSelection.CODEC.fieldOf("terrain").forGetter(LevelSelection::terrain),
                SystemsData.SystemSelection.CODEC.fieldOf("biome").forGetter(LevelSelection::biome))
                .apply(i, LevelSelection::new));
    }
}
