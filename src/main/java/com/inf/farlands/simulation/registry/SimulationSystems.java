package com.inf.farlands.simulation.registry;

import com.inf.farlands.simulation.int64.SimulationNoiseSystem;
import com.inf.farlands.terrain.registry.SystemId;

/**
 * 外部模组登记模拟层系统的入口，与 {@code FarlandsSystems} 同形。
 *
 * <p>
 * 登记发生在模组初始化期，早于任何世界加载：档案在 level 构造时才解析，远晚于此。实现类的硬契约与 farlands
 * 那边一致，违反者在这里就抛，不留到运行时。
 */
public final class SimulationSystems {

    private SimulationSystems() {
    }

    public static void registerNoise(SystemId id, Class<? extends SimulationNoiseSystem> type) {
        verifyConstructor(id, type);
        SimulationRegistries.register(id, type);
    }

    /**
     * 构造器形状检查。反射取的是唯一构造器，多一个就取错，私有会让 {@code newInstance} 直接抛。
     */
    private static void verifyConstructor(SystemId id, Class<?> type) {
        var constructors = type.getDeclaredConstructors();
        if (constructors.length != 1
                || !java.lang.reflect.Modifier.isPublic(constructors[0].getModifiers())
                || constructors[0].getParameterCount() != 1
                || constructors[0].getParameterTypes()[0] != com.inf.farlands.terrain.registry.SystemArgs.class) {
            throw new IllegalArgumentException(
                    "farlands: simulation system %s (%s) must have exactly one public constructor taking SystemArgs"
                            .formatted(id, type.getName()));
        }
    }
}
