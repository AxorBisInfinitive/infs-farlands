package com.inf.farlands.simulation;

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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * 服务端的模拟段存储：按维度分表的纯内存结构，不做持久化。
 *
 * <p>
 * 生命周期由 {@link SimulationServer} 驱动：段第一次进入某个玩家的视距时创建，最后一个玩家离开后释放。写入口
 * 只有生产者与将来的数据写入方，两侧都只在服务端主线程，所以内层表用普通 {@link HashMap}，内层也没有加锁。
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
     * 建一段并跑一次生产者。已存在时直接返回旧的那一份，不重复生产。
     *
     * <p>
     * 新段一律置脏：它还没发给任何人。
     */
    public static SimulationSectionData create(ServerLevel level, SimulationSectionKey key, SimulationProducer producer) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.computeIfAbsent(level.dimension(),
                k -> new HashMap<>());
        SimulationSectionData existing = sections.get(key);
        if (existing != null) {
            return existing;
        }
        SimulationSectionData data = new SimulationSectionData(newSection(level));
        producer.produce(key, data);
        data.markDirty();
        sections.put(key, data);
        return data;
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

    /** 释放一段，只放内存。 */
    public static void release(ResourceKey<Level> dimension, SimulationSectionKey key) {
        Map<SimulationSectionKey, SimulationSectionData> sections = STORE.get(dimension);
        if (sections == null) {
            return;
        }
        sections.remove(key);
        if (sections.isEmpty()) {
            STORE.remove(dimension);
        }
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
