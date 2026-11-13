package com.inf.farlands.simulation.registry;

import java.util.List;
import java.util.function.Function;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.int64.SimulationNoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemRegistry;

import net.minecraft.resources.Identifier;

/**
 * 模拟层系统的类型化注册表。
 *
 * <p>
 * 只有一族，用的却是 farlands 那边同一份 {@link SystemRegistry}：类型化注册表这件事与层无关，复制第二份只会
 * 让注册期校验与反射实例化两处出现分歧。
 *
 * <p>
 * id 用 {@link InfsFarlands#MOD_ID} 拼，与 {@code SystemRegistries} 同一个理由：该常量会被 javac 内联，不触发
 * {@code InfsFarlands} 的类初始化，而 {@code FarlandsRegister.registerStatic} 正从那个类的静态块里调到本类。
 */
public final class SimulationRegistries {

    private static final SystemRegistry<SimulationNoiseSystem> NOISE =
            new SystemRegistry<>(SimulationNoiseSystem.class);

    /** 全空气，与 farlands 的 VoidNoiseSystem 同形。 */
    public static final SystemId NOISE_VOID = id("simulation_void_noise_system");

    /** 四路八度噪声加一条密度链，穹顶按 int32 的索引上界在 int64 域内形成。 */
    public static final SystemId NOISE_CWG = id("simulation_cwg_noise_system");

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

    /**
     * id 到实现类的取法，形状与 farlands 那边 {@code classOf(kind)} 一致。
     *
     * <p>参数校验与落盘还原都经它取声明，两处因此共用同一段判据。
     */
    public static Function<SystemId, Class<?>> classLookup() {
        return id -> classOf(id);
    }
}
