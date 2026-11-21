package com.inf.farlands.simulation.registry;

import java.util.List;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.int1024.SimulationBiomeSystem;
import com.inf.farlands.simulation.int1024.SimulationTerrainSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemRegistry;

import net.minecraft.resources.Identifier;

/**
 * 模拟层系统的类型化注册表，两族：地形与群系。
 *
 * <p>
 * 地形那一族的表按基接口 {@link SimulationTerrainSystem} 收，与 farlands 的 {@code SystemRegistry<TerrainSystem>}
 * 同形：同一张表里放得下密度链与逐段两种范式，范式之分在填充器的分派里，不在注册表里。
 *
 * <p>
 * 两族用的都是 farlands 那边同一份 {@link SystemRegistry}：类型化注册表这件事与层无关，复制第二份只会让注册期
 * 校验与反射实例化两处出现分歧。
 *
 * <p>
 * id 用 {@link InfsFarlands#MOD_ID} 拼，与 {@code SystemRegistries} 同一个理由：该常量会被 javac 内联，不触发
 * {@code InfsFarlands} 的类初始化，而 {@code FarlandsRegister.registerStatic} 正从那个类的静态块里调到本类。
 */
public final class SimulationRegistries {

    private static final SystemRegistry<SimulationTerrainSystem> TERRAIN =
            new SystemRegistry<>(SimulationTerrainSystem.class);

    private static final SystemRegistry<SimulationBiomeSystem> BIOME =
            new SystemRegistry<>(SimulationBiomeSystem.class);

    /** 密度链范式，全空气，与 farlands 的 VoidNoiseSystem 同形。 */
    public static final SystemId NOISE_VOID = id("simulation_void_noise_system");

    /** 逐段范式，一个方块都不写。 */
    public static final SystemId SECTION_VOID = id("simulation_void_section_system");

    /** 逐块范式，每格都是空气。 */
    public static final SystemId BLOCK_VOID = id("simulation_void_block_system");

    /** 恒 the_void，与建段的群系默认同值。 */
    public static final SystemId BIOME_VOID = id("simulation_void_biome_system");

    private SimulationRegistries() {
    }

    private static SystemId id(String path) {
        return new SystemId(Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, path));
    }

    /** 登记一个地形实现类。实现接口与声明的校验在 {@link SystemRegistry#register} 里。 */
    public static void register(SystemId id, Class<? extends SimulationTerrainSystem> type) {
        TERRAIN.register(id, type);
    }

    public static SimulationTerrainSystem newInstance(SystemId id, SystemArgs args) {
        return TERRAIN.newInstance(id, args);
    }

    public static Class<? extends SimulationTerrainSystem> classOf(SystemId id) {
        return TERRAIN.classOf(id);
    }

    public static boolean contains(SystemId id) {
        return TERRAIN.contains(id);
    }

    /** 地形那一族登记顺序的快照，供界面列选项。两种范式都在这一张表里。 */
    public static List<SystemId> ids() {
        return TERRAIN.ids();
    }

    /** 登记一个群系实现类。 */
    public static void registerBiome(SystemId id, Class<? extends SimulationBiomeSystem> type) {
        BIOME.register(id, type);
    }

    public static SimulationBiomeSystem biome(SystemId id, SystemArgs args) {
        return BIOME.newInstance(id, args);
    }

    public static Class<? extends SimulationBiomeSystem> biomeClassOf(SystemId id) {
        return BIOME.classOf(id);
    }

    public static boolean biomeContains(SystemId id) {
        return BIOME.contains(id);
    }

    /** 群系那一族登记顺序的快照，供界面列选项。 */
    public static List<SystemId> biomeIds() {
        return BIOME.ids();
    }
}
