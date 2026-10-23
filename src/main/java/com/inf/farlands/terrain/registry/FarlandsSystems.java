package com.inf.farlands.terrain.registry;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.DecorationSystem;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.TerrainSystem;

/**
 * 外部模组登记地形系统的入口。本模组自己的内置系统走 {@code FarlandsRegister.registerStatic}，
 * 外部的在各自的 mod 初始化期调这里，两者写的是同一批注册表。
 *
 * <p>登记时机：注册表是进程内状态，不落盘。所有登记必须在世界加载之前完成，也就是 mod 初始化期；
 * 世界的 {@code systems.dat} 在 level 构造时才解析，远晚于此。
 *
 * <p>系统与维度**没有**绑定关系。登记一个系统不限定它给哪个维度用，界面会把该族的全部系统列给每一
 * 个维度页，任何维度都能选任何一个。系统自己若依赖某个维度的形状，那是它自己的事，注册表不表达、
 * 也不校验。
 *
 * <p><b>实现类的硬契约</b>，违反者在这里就抛，不留到运行时：
 * <ul>
 * <li>必须实现对应的族接口。</li>
 * <li>必须恰好一个构造器，可见性 public，形参是 {@link SystemArgs}。实例化走
 * {@code SystemRegistry} 的反射：取 {@code getDeclaredConstructors()[0]} 且不做
 * {@code setAccessible}。</li>
 * <li>参数声明用 {@code @SystemDefaultParams} 标在一个 {@code static final SystemParams} 字段上，
 * 与内置系统同形。没有该字段、字段多于一个或形状不对，登记时即抛：不存在「没有声明」这个状态，
 * 也不想参数的系统必须显式写 {@code SystemParams.EMPTY}。</li>
 * <li>id 不得与既有登记重复，命名空间不限。</li>
 * </ul>
 */
public final class FarlandsSystems {

    private FarlandsSystems() {
    }

    public static void registerTerrain(SystemId id, Class<? extends TerrainSystem> type) {
        verifyConstructor(id, type);
        SystemRegistries.registerTerrain(id, type);
    }

    public static void registerBiome(SystemId id, Class<? extends BiomeSystem> type) {
        verifyConstructor(id, type);
        SystemRegistries.registerBiome(id, type);
    }

    public static void registerSurface(SystemId id, Class<? extends SurfaceSystem> type) {
        verifyConstructor(id, type);
        SystemRegistries.registerSurface(id, type);
    }

    public static void registerCarver(SystemId id, Class<? extends CarverSystem> type) {
        verifyConstructor(id, type);
        SystemRegistries.registerCarver(id, type);
    }

    public static void registerDecoration(SystemId id, Class<? extends DecorationSystem> type) {
        verifyConstructor(id, type);
        SystemRegistries.registerDecoration(id, type);
    }

    /**
     * 构造器形状检查。族接口是否实现由 {@code SystemRegistry.register} 兜住，这里只补它兜不住的那条：
     * 反射取的是唯一构造器，多一个就取错，私有会让 {@code newInstance} 直接抛。
     */
    private static void verifyConstructor(SystemId id, Class<?> type) {
        var constructors = type.getDeclaredConstructors();
        if (constructors.length != 1
                || !java.lang.reflect.Modifier.isPublic(constructors[0].getModifiers())
                || constructors[0].getParameterCount() != 1
                || constructors[0].getParameterTypes()[0] != SystemArgs.class) {
            throw new IllegalArgumentException(
                    "farlands: system %s (%s) must have exactly one public constructor taking SystemArgs"
                            .formatted(id, type.getName()));
        }
    }
}
