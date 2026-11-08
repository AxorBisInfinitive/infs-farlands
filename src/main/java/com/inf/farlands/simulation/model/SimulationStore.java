package com.inf.farlands.simulation.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * 服务端的模拟段存储：按维度分表的纯内存结构，不做持久化。
 *
 * <p>
 * 生命周期由 {@code com.inf.farlands.simulation.SimulationServer} 驱动：段第一次进入某个玩家的视距时创建，
 * 最后一个玩家离开后释放。写入口只有生产者与将来的数据写入方，两侧都只在服务端主线程，所以内层表用普通
 * {@link HashMap}，内层也没有加锁。
 *
 * <p>
 * 段内容用 {@link LevelChunkSection} 承载，工厂取 {@code level.palettedContainerFactory()}，与客户端同一套
 * 调色板策略，线上传播直接借它的 {@code write} 与 {@code read}。
 *
 * <p>
 * 群系容器单独建，默认取 {@code minecraft:the_void}。工厂的默认是平原，而模拟之地一直是 void，直接借单参
 * 构造器会把整层的群系换成平原，草的 tint 与边缘混合都跟着变。
 */
public final class SimulationStore {

    private static final Map<ResourceKey<Level>, Map<SimulationSectionKey, SimulationSectionData>> STORE = new HashMap<>();

    /** 每次注册表各自的 void 群系，弱键跟随注册表生命周期。 */
    private static final Map<RegistryAccess, Holder<Biome>> VOID_BIOMES = new WeakHashMap<>();

    private SimulationStore() {
    }

    /** 该维度该段是否在表里。 */
    public static boolean contains(ResourceKey<Level> dimension, SimulationSectionKey key) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.get(dimension);
        return sections != null && sections.containsKey(key);
    }

    /** 该维度该段，没有则 null。 */
    public static SimulationSectionData get(ResourceKey<Level> dimension, SimulationSectionKey key) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.get(dimension);
        return sections == null ? null : sections.get(key);
    }

    /**
     * 建一段空壳，不跑生产者。内容由调用方在池上填，填完自己升档。
     *
     * <p>
     * 已存在时直接返回旧的那一份。新段不置下发脏位：它此刻还没有内容，发也是空的。
     */
    public static SimulationSectionData createEmpty(ServerLevel level, SimulationSectionKey key) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.computeIfAbsent(level.dimension(),
                k -> new HashMap<>());
        SimulationSectionData existing = sections.get(key);
        if (existing != null) {
            return existing;
        }
        SimulationSectionData data = new SimulationSectionData(newSection(level));
        sections.put(key, data);
        return data;
    }

    /**
     * 读回路径的建段：容器与阶段都来自盘上，不再跑生产者。
     *
     * <p>
     * 走新建实例而不是复用旧对象，因为 {@code section} 是 final 字段，换不了容器。同 key 已有条目时直接替换。
     *
     * <p>
     * 落盘位不设：内容与盘上一致。下发由调用方经 {@code SimulationServer.noteDirty} 登记。
     */
    public static SimulationSectionData putDecoded(ResourceKey<Level> dimension, SimulationSectionKey key,
            LevelChunkSection section, int shapeMode, DataLayer blockLight, DataLayer skyLight, int stage) {
        SimulationSectionData data = new SimulationSectionData(section);
        data.setShapeMode(shapeMode);
        if (blockLight != null) {
            data.blockLight = blockLight;
        }
        if (skyLight != null) {
            data.skyLight = skyLight;
        }
        data.advanceTo(stage);
        STORE.computeIfAbsent(dimension, k -> new HashMap<>()).put(key, data);
        return data;
    }

    /**
     * 条件摘除：只有表里那一条就是 expected 时才摘，返回是否真的摘掉。
     *
     * <p>
     * 落盘提交回调与任务收尾都走它。不做条件判断的话，同 key 的新一代会被旧一代的收尾摘掉。
     */
    public static boolean removeIfSame(ResourceKey<Level> dimension, SimulationSectionKey key,
            SimulationSectionData expected) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.get(dimension);
        if (sections == null || sections.get(key) != expected) {
            return false;
        }
        sections.remove(key);
        if (sections.isEmpty()) {
            STORE.remove(dimension);
        }
        return true;
    }

    /** 撤销 releasing 标记：该段在落盘提交完成前又被需要，复用原对象。返回是否命中。 */
    public static boolean unmarkReleasing(ResourceKey<Level> dimension, SimulationSectionKey key) {
        SimulationSectionData data = get(dimension, key);
        if (data == null || !data.isReleasing()) {
            return false;
        }
        data.clearReleasing();
        return true;
    }

    /** 新段的容器：块用工厂的默认空气，群系用 void。 */
    private static LevelChunkSection newSection(ServerLevel level) {
        PalettedContainerFactory factory = level.palettedContainerFactory();
        PalettedContainer<BlockState> states = factory.createForBlockStates();
        PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(voidBiome(level), factory.biomeStrategy());
        return new LevelChunkSection(states, biomes);
    }

    /**
     * 该次注册表的 void 群系。按 {@link RegistryAccess} 缓存。
     *
     * <p>
     * 键是注册表本身：换世界会重建注册表，旧的 {@code Holder} 与新容器不配套，用它序列化会写出旧 id。弱键让旧
     * 世界的条目随注册表被回收，{@link #clearAll()} 里再清一次，比等 GC 确定。
     *
     * <p>
     * 只在服务端主线程读写，不需要并发容器。
     */
    private static Holder<Biome> voidBiome(ServerLevel level) {
        RegistryAccess registries = level.registryAccess();
        Holder<Biome> cached = VOID_BIOMES.get(registries);
        if (cached != null) {
            return cached;
        }
        Holder<Biome> resolved = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
        VOID_BIOMES.put(registries, resolved);
        return resolved;
    }

    /** 该维度现存段的键，拷贝一份供遍历时安全删除。 */
    public static Collection<SimulationSectionKey> keys(ResourceKey<Level> dimension) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.get(dimension);
        return sections == null ? List.of() : new ArrayList<>(sections.keySet());
    }

    /** 该维度现存段数，供读数与统计。 */
    public static int size(ResourceKey<Level> dimension) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.get(dimension);
        return sections == null ? 0 : sections.size();
    }

    /** 停服清空。群系缓存一并清，不等弱键回收。 */
    public static void clearAll() {
        STORE.clear();
        VOID_BIOMES.clear();
    }
}
