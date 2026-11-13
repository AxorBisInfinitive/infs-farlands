package com.inf.farlands.simulation.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.registry.SystemsData;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 模拟层的系统选择，落盘为 {@code data/infs-farlands/simulation.dat}。
 *
 * <p>
 * 与 {@code systems.dat} 分开：给旧档加必填字段会让读盘直接抛，这是引入新档案时唯一不伤旧世界的做法。
 * 结构是维度到一族的选择，复用 {@link SystemsData.SystemSelection} 与它的参数编码，不复制第二套参数类型。
 *
 * <p>
 * 选择是唯一来源。生成用的系统与档案里的系统永远同源，因为没有任何路径能在已有世界上改选择。
 */
public final class SimulationData extends SavedData {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "simulation");

    public static final Codec<SimulationData> CODEC = Codec
            .unboundedMap(Identifier.CODEC, SystemsData.SystemSelection.CODEC)
            .xmap(SimulationData::new, SimulationData::systems);

    /**
     * 与 {@code SystemsData} 取同一个 dataFixType：本 port 不走 vanilla 的读路径，该类别实际不被用到，
     * 取一个 remainder 常量是为了即便被碰到也不改写内容。
     */
    public static final SavedDataType<SimulationData> TYPE = new SavedDataType<>(
            ID, SimulationData::new, CODEC, DataFixTypes.SAVED_DATA_WEATHER);

    private final Map<Identifier, SystemsData.SystemSelection> systems;

    /** SavedDataType 要求无参构造器；本 port 不走 computeIfAbsent，这个空对象不会被采用。 */
    public SimulationData() {
        this(Map.of());
    }

    public SimulationData(Map<Identifier, SystemsData.SystemSelection> systems) {
        this.systems = Collections.unmodifiableMap(new LinkedHashMap<>(systems));
    }

    /** 维度到选择，保插入顺序以便落盘可读。 */
    public Map<Identifier, SystemsData.SystemSelection> systems() {
        return this.systems;
    }

    /** 该维度的选择，缺失返回 null，由调用方决定失败语义。 */
    public SystemsData.SystemSelection selection(Identifier dimension) {
        return this.systems.get(dimension);
    }
}
