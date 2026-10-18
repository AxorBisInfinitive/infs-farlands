package com.inf.farlands.terrain.registry;

import java.util.List;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.DecorationSystem;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.TerrainSystem;

import net.minecraft.resources.Identifier;

/**
 * 系统注册表的静态门面：五张类型化表、21 个内置 id 常量、五个登记入口、五个取用入口与元信息。
 *
 * <p>
 * 门面不读配置也不引 level：维度到 id 的选择由调用点给出，注册表层因此不依赖任何上层。
 *
 * <p>
 * id 用 InfsFarlands.MOD_ID 拼，不调 InfsFarlands.id：前者是编译期常量会被 javac 内联，不触发
 * InfsFarlands 的类初始化；后者是方法调用会触发，而 FarlandsRegister.registerStatic 正从
 * InfsFarlands 的静态块里调到本类，触发就会形成重入初始化。
 */
public final class SystemRegistries {

    private static final SystemRegistry<TerrainSystem> TERRAIN = new SystemRegistry<>(TerrainSystem.class);
    private static final SystemRegistry<BiomeSystem> BIOME = new SystemRegistry<>(BiomeSystem.class);
    private static final SystemRegistry<SurfaceSystem> SURFACE = new SystemRegistry<>(SurfaceSystem.class);
    private static final SystemRegistry<CarverSystem> CARVER = new SystemRegistry<>(CarverSystem.class);
    private static final SystemRegistry<DecorationSystem> DECORATION = new SystemRegistry<>(DecorationSystem.class);

    // id 与常量名都带所属维度，与实现类的包结构同序：族、维度、实现。misc 包取 misc，overworld 包取
    // overworld。

    public static final SystemId TERRAIN_MISC_VOID_NOISE_SYSTEM = id("misc_void_noise_system");
    public static final SystemId TERRAIN_OVERWORLD_VANILLA_NOISE_SYSTEM = id("overworld_vanilla_noise_system");
    public static final SystemId TERRAIN_OVERWORLD_BETA_1_7_3_NOISE_SYSTEM = id(
            "overworld_beta_1_7_3_noise_system");
    public static final SystemId TERRAIN_MISC_HEX_NOISE_SYSTEM = id("misc_hex_noise_system");
    public static final SystemId TERRAIN_MISC_WEIERSTRASS_NOISE_SYSTEM = id("misc_weierstrass_noise_system");
    public static final SystemId TERRAIN_OVERWORLD_OCT_NOISE_SYSTEM = id("overworld_oct_noise_system");
    public static final SystemId TERRAIN_OVERWORLD_INFDEV_20100226_BLOCK_SYSTEM = id(
            "overworld_infdev_20100226_block_system");
    public static final SystemId TERRAIN_MISC_SIERPINSKI_PYRAMID_BLOCK_SYSTEM = id(
            "misc_sierpinski_pyramid_block_system");
    public static final SystemId TERRAIN_MISC_THE_FOURTH_DIMENSION_BLOCK_SYSTEM = id(
            "misc_the_fourth_dimension_block_system");
    public static final SystemId TERRAIN_OVERWORLD_CWG_NOISE_SYSTEM = id("overworld_cwg_noise_system");
    public static final SystemId TERRAIN_OVERWORLD_VANILLA_263_NOISE_SYSTEM = id(
            "overworld_vanilla_263_noise_system");
    public static final SystemId TERRAIN_MISC_SKY_GIRL_NOISE_SYSTEM = id("misc_sky_girl_noise_system");

    public static final SystemId BIOME_MISC_VOID_BIOME_SYSTEM = id("misc_void_biome_system");
    public static final SystemId BIOME_OVERWORLD_VANILLA_BIOME_SYSTEM = id("overworld_vanilla_biome_system");
    public static final SystemId BIOME_OVERWORLD_OCT_BIOME_SYSTEM = id("overworld_oct_biome_system");

    public static final SystemId SURFACE_MISC_VOID_SURFACE_SYSTEM = id("misc_void_surface_system");
    public static final SystemId SURFACE_OVERWORLD_VANILLA_SURFACE_SYSTEM = id("overworld_vanilla_surface_system");

    public static final SystemId CARVER_MISC_VOID_CARVER_SYSTEM = id("misc_void_carver_system");
    public static final SystemId CARVER_OVERWORLD_VANILLA_CARVER_SYSTEM = id("overworld_vanilla_carver_system");

    public static final SystemId DECORATION_MISC_VOID_DECORATION_SYSTEM = id("misc_void_decoration_system");
    public static final SystemId DECORATION_OVERWORLD_VANILLA_DECORATION_SYSTEM = id(
            "overworld_vanilla_decoration_system");

    private SystemRegistries() {
    }

    private static SystemId id(String path) {
        return new SystemId(Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, path));
    }

    // ---- 登记 ----

    public static void registerTerrain(SystemId id, Class<? extends TerrainSystem> type) {
        TERRAIN.register(id, type);
    }

    public static void registerBiome(SystemId id, Class<? extends BiomeSystem> type) {
        BIOME.register(id, type);
    }

    public static void registerSurface(SystemId id, Class<? extends SurfaceSystem> type) {
        SURFACE.register(id, type);
    }

    public static void registerCarver(SystemId id, Class<? extends CarverSystem> type) {
        CARVER.register(id, type);
    }

    public static void registerDecoration(SystemId id, Class<? extends DecorationSystem> type) {
        DECORATION.register(id, type);
    }

    // ---- 取用 ----

    public static TerrainSystem terrain(SystemId id, SystemArgs args) {
        return TERRAIN.newInstance(id, args);
    }

    public static BiomeSystem biome(SystemId id, SystemArgs args) {
        return BIOME.newInstance(id, args);
    }

    public static SurfaceSystem surface(SystemId id, SystemArgs args) {
        return SURFACE.newInstance(id, args);
    }

    public static CarverSystem carver(SystemId id, SystemArgs args) {
        return CARVER.newInstance(id, args);
    }

    public static DecorationSystem decoration(SystemId id, SystemArgs args) {
        return DECORATION.newInstance(id, args);
    }

    // ---- 元信息 ----

    static SystemRegistry<TerrainSystem> terrainTable() {
        return TERRAIN;
    }

    static SystemRegistry<BiomeSystem> biomeTable() {
        return BIOME;
    }

    static SystemRegistry<SurfaceSystem> surfaceTable() {
        return SURFACE;
    }

    static SystemRegistry<CarverSystem> carverTable() {
        return CARVER;
    }

    static SystemRegistry<DecorationSystem> decorationTable() {
        return DECORATION;
    }

    public static List<SystemId> terrainIds() {
        return TERRAIN.ids();
    }

    public static List<SystemId> biomeIds() {
        return BIOME.ids();
    }

    public static List<SystemId> surfaceIds() {
        return SURFACE.ids();
    }

    public static List<SystemId> carverIds() {
        return CARVER.ids();
    }

    public static List<SystemId> decorationIds() {
        return DECORATION.ids();
    }

    public static Class<? extends TerrainSystem> terrainClassOf(SystemId id) {
        return TERRAIN.classOf(id);
    }

    public static Class<? extends BiomeSystem> biomeClassOf(SystemId id) {
        return BIOME.classOf(id);
    }

    public static Class<? extends SurfaceSystem> surfaceClassOf(SystemId id) {
        return SURFACE.classOf(id);
    }

    public static Class<? extends CarverSystem> carverClassOf(SystemId id) {
        return CARVER.classOf(id);
    }

    public static Class<? extends DecorationSystem> decorationClassOf(SystemId id) {
        return DECORATION.classOf(id);
    }

    public static boolean terrainContains(SystemId id) {
        return TERRAIN.contains(id);
    }

    public static boolean biomeContains(SystemId id) {
        return BIOME.contains(id);
    }

    public static boolean surfaceContains(SystemId id) {
        return SURFACE.contains(id);
    }

    public static boolean carverContains(SystemId id) {
        return CARVER.contains(id);
    }

    public static boolean decorationContains(SystemId id) {
        return DECORATION.contains(id);
    }
}
