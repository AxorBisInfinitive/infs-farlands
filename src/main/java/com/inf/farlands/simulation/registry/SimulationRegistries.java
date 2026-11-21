package com.inf.farlands.simulation.registry;

import java.util.List;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.int1024.SimulationBiomeSystem;
import com.inf.farlands.simulation.int1024.SimulationNoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemRegistry;

import net.minecraft.resources.Identifier;

/**
 * 模拟层系统的类型化注册表，两族：噪声与群系。
 *
 * <p>
 * 两族用的都是 farlands 那边同一份 {@link SystemRegistry}：类型化注册表这件事与层无关，复制第二份只会让注册期
 * 校验与反射实例化两处出现分歧。
 *
 * <p>
 * 噪声那一族的取用方法与 farlands 那边同名，群系那一族的带 {@code biome} 前缀。两族的取用面不合并，因为返回类型
 * 不同，合并只能靠泛型参数，调用点反而要多写一次类型。
 *
 * <p>
 * id 用 {@link InfsFarlands#MOD_ID} 拼，与 {@code SystemRegistries} 同一个理由：该常量会被 javac 内联，不触发
 * {@code InfsFarlands} 的类初始化，而 {@code FarlandsRegister.registerStatic} 正从那个类的静态块里调到本类。
 */
public final class SimulationRegistries {

    private static final SystemRegistry<SimulationNoiseSystem> NOISE =
            new SystemRegistry<>(SimulationNoiseSystem.class);

    private static final SystemRegistry<SimulationBiomeSystem> BIOME =
            new SystemRegistry<>(SimulationBiomeSystem.class);

    /** 全空气，与 farlands 的 VoidNoiseSystem 同形。 */
    public static final SystemId NOISE_VOID = id("simulation_void_noise_system");

    /** 恒 the_void，与建段的群系默认同值。 */
    public static final SystemId BIOME_VOID = id("simulation_void_biome_system");

    private SimulationRegistries() {
    }

    private static SystemId id(String path) {
        return new SystemId(Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, path));
    }

    /** 登记一个实现类。实现接口与声明的校验在 {@link SystemRegistry#register} 里。 */
    public static void register(SystemId id, Class<? extends SimulationNoiseSystem> type) {
        NOISE.register(id, type);
    }

    public static SimulationNoiseSystem newInstance(SystemId id, SystemArgs args) {
        return NOISE.newInstance(id, args);
    }

    public static Class<? extends SimulationNoiseSystem> classOf(SystemId id) {
        return NOISE.classOf(id);
    }

    public static boolean contains(SystemId id) {
        return NOISE.contains(id);
    }

    /** 登记顺序的快照，供界面列选项。 */
    public static List<SystemId> ids() {
        return NOISE.ids();
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
