package com.inf.farlands.terrain.registry;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.DecorationSystem;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.registry.SystemsData.LevelSelection;
import com.inf.farlands.terrain.registry.SystemsData.SystemSelection;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 系统配置的读写与落地。
 *
 * <p>
 * 读盘骨架在 {@link SelectionFile}：严格自读、已初始化却缺档即抛、缺档取暂存再取默认、按 server 解析一次。
 * 本类只提供 farlands 这一层的编码、默认表与「还原成界面文本」那一层校验。
 *
 * <p>
 * 写借 {@code DataStorage}：{@code set} 只标脏进缓存，由既有的保存节奏落盘，编码用同一份 {@code Codec}。
 */
public final class SystemsIO {

    private static final SelectionFile<SystemsData> FILE =
            new SelectionFile<>(SystemsData.TYPE, SystemsData.CODEC);

    private SystemsIO() {
    }

    /** 配置文件路径，与 {@code SavedDataStorage} 的取法同式。 */
    public static Path file(MinecraftServer server) {
        return FILE.file(server);
    }

    /** 某个世界根目录下的配置文件路径。启动期与选择世界页因此共用同一份算式。 */
    public static Path file(Path worldRoot) {
        return FILE.file(worldRoot);
    }

    /**
     * 该世界根目录下的配置能不能用。
     *
     * <p>两层：{@link #read} 与 {@link #texts}。文件读得出不代表能用：{@code Arg} 的值经
     * {@code Codec.PASSTHROUGH} 原样带过，声明类型与标签类型不符要等到还原文本时才暴露，未注册的系统 id 要
     * 等到取类时才暴露。第二层与界面预填共用 {@link #texts}，所以「列表说能打开、预填时才炸」不会出现。
     */
    public static boolean compatible(Path worldRoot) {
        return FILE.compatible(worldRoot, data -> {
            for (LevelSelection selection : data.levels().values()) {
                texts(FamilyKind.TERRAIN, selection.terrain());
                texts(FamilyKind.BIOME, selection.biome());
                texts(FamilyKind.SURFACE, selection.surface());
                texts(FamilyKind.CARVER, selection.carver());
                texts(FamilyKind.DECORATION, selection.decoration());
            }
        });
    }

    /**
     * 把一族的选择还原成界面文本，键与界面上的框一一对应。
     *
     * <p>逐项按声明类型还原，文件里缺的项不出现。界面预填与兼容性判据共用本方法，两处不会漂移。
     *
     * <p>失败一律抛：未注册的系统 id、声明类型与标签类型不符都在这里暴露，由调用方决定是中止还是判成不兼容。
     */
    public static Map<String, String> texts(FamilyKind kind, SystemSelection selection) {
        SystemId id = new SystemId(selection.id());
        SystemParams declared = SystemParams.declaredBy(classOf(kind, id));
        Map<String, String> out = new LinkedHashMap<>();
        for (SystemParamSpec spec : declared.entries()) {
            Arg arg = selection.args().get(spec.key());
            if (arg != null) {
                out.put(spec.key(), SystemParamSpec.text(spec.key(), spec.type(), arg.value()));
            }
        }
        return out;
    }

    /** 按族取实现类。未注册即抛，是兼容性判据第二层的另一半。 */
    public static Class<?> classOf(FamilyKind kind, SystemId id) {
        return switch (kind) {
            case BIOME -> SystemRegistries.biomeClassOf(id);
            case TERRAIN -> SystemRegistries.terrainClassOf(id);
            case SURFACE -> SystemRegistries.surfaceClassOf(id);
            case CARVER -> SystemRegistries.carverClassOf(id);
            case DECORATION -> SystemRegistries.decorationClassOf(id);
        };
    }

    /** 创建世界页签的选择在 level 构造之前的暂存。 */
    public static void stageForLevelCreation(SystemsData data) {
        FILE.stageForLevelCreation(data);
    }

    public static SystemsData systemsFor(MinecraftServer server) {
        return FILE.systemsFor(server, SystemsIO::defaultFor);
    }

    /** 解析该世界的配置：文件在则严格自读，不在则按新世界落默认并写回，其余情形抛。 */
    public static SystemsData resolve(MinecraftServer server) {
        return FILE.systemsFor(server, SystemsIO::defaultFor);
    }

    /** 严格自读。任何异常都带上文件路径直穿，不做回退也不新建默认。 */
    public static SystemsData read(Path file) {
        return FILE.read(file);
    }

    /**
     * 该维度的选择。缺失时补该族 VOID 并照常返回，不抛。
     *
     * <p>缺失是正常情形而非错误：世界维度由数据包与模组决定，配置文件写在某个时刻，之后维度集合可能变。
     * 按既定语义，非原版维度的默认选择是 VOID，所以缺失等价于「没为它配过」，补上即可。
     */
    public static LevelSelection selection(SystemsData data, Identifier dimension) {
        LevelSelection selection = data.selection(dimension);
        return selection != null ? selection : voidSelection();
    }

    /**
     * 内置默认。原版三个维度各取各的 vanilla 五族，其余维度取 {@link #voidSelection()}。
     * 维度取注册表里的全部 LevelStem，新世界因此不会因为数据包加了维度而缺条目。
     */
    public static SystemsData defaultFor(MinecraftServer server) {
        Map<Identifier, LevelSelection> levels = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : server.registryAccess()
                .lookupOrThrow(Registries.LEVEL_STEM).entrySet()) {
            Identifier dimension = entry.getKey().identifier();
            levels.put(dimension, defaultSelection(dimension));
        }
        return new SystemsData(levels);
    }

    /**
     * 该维度的默认五族选择：原版三个维度各取各的 vanilla，其余维度五族全 VOID。
     *
     * <p>判据只有这一处。新世界的维度集合来自界面暂存，{@link #defaultFor} 那条路在新世界走不到，所以界面
     * 建页与落盘默认都经 {@link #defaultId} 取同一份判据，两处各写一份会分叉。
     */
    public static LevelSelection defaultSelection(Identifier dimension) {
        VanillaDefaults defaults = vanillaDefaultsOf(dimension);
        if (defaults == null) {
            return voidSelection();
        }
        return new LevelSelection(
                vanillaSelection(defaults.terrain()),
                vanillaSelection(defaults.biome()),
                vanillaSelection(defaults.surface()),
                vanillaSelection(defaults.carver()),
                vanillaSelection(defaults.decoration()));
    }

    /** 该维度该族的默认 id，供界面给下拉设初始选中。非原版维度给该族的 VOID。 */
    public static SystemId defaultId(Identifier dimension, FamilyKind kind) {
        VanillaDefaults defaults = vanillaDefaultsOf(dimension);
        if (defaults == null) {
            return voidId(kind);
        }
        return switch (kind) {
            case TERRAIN -> defaults.terrain();
            case BIOME -> defaults.biome();
            case SURFACE -> defaults.surface();
            case CARVER -> defaults.carver();
            case DECORATION -> defaults.decoration();
        };
    }

    /** 一个原版维度默认取的五族 id。 */
    private record VanillaDefaults(SystemId terrain, SystemId biome, SystemId surface, SystemId carver,
            SystemId decoration) {
    }

    private static final VanillaDefaults OVERWORLD_DEFAULTS = new VanillaDefaults(
            SystemRegistries.TERRAIN_OVERWORLD_VANILLA_NOISE_SYSTEM,
            SystemRegistries.BIOME_OVERWORLD_VANILLA_BIOME_SYSTEM,
            SystemRegistries.SURFACE_OVERWORLD_VANILLA_SURFACE_SYSTEM,
            SystemRegistries.CARVER_OVERWORLD_VANILLA_CARVER_SYSTEM,
            SystemRegistries.DECORATION_OVERWORLD_VANILLA_DECORATION_SYSTEM);

    private static final VanillaDefaults NETHER_DEFAULTS = new VanillaDefaults(
            SystemRegistries.TERRAIN_THE_NETHER_VANILLA_NOISE_SYSTEM,
            SystemRegistries.BIOME_THE_NETHER_VANILLA_BIOME_SYSTEM,
            SystemRegistries.SURFACE_THE_NETHER_VANILLA_SURFACE_SYSTEM,
            SystemRegistries.CARVER_THE_NETHER_VANILLA_CARVER_SYSTEM,
            SystemRegistries.DECORATION_THE_NETHER_VANILLA_DECORATION_SYSTEM);

    private static final VanillaDefaults END_DEFAULTS = new VanillaDefaults(
            SystemRegistries.TERRAIN_THE_END_VANILLA_NOISE_SYSTEM,
            SystemRegistries.BIOME_THE_END_VANILLA_BIOME_SYSTEM,
            SystemRegistries.SURFACE_THE_END_VANILLA_SURFACE_SYSTEM,
            SystemRegistries.CARVER_THE_END_VANILLA_CARVER_SYSTEM,
            SystemRegistries.DECORATION_THE_END_VANILLA_DECORATION_SYSTEM);

    /** 该维度的默认 id 组；非原版维度返回 null。 */
    private static VanillaDefaults vanillaDefaultsOf(Identifier dimension) {
        if (Level.OVERWORLD.identifier().equals(dimension)) {
            return OVERWORLD_DEFAULTS;
        }
        if (Level.NETHER.identifier().equals(dimension)) {
            return NETHER_DEFAULTS;
        }
        if (Level.END.identifier().equals(dimension)) {
            return END_DEFAULTS;
        }
        return null;
    }

    /** 该族 VOID 的 id。缺失维度与创建页里的非原版维度都用它兜底。 */
    private static SystemId voidId(FamilyKind kind) {
        return switch (kind) {
            case TERRAIN -> SystemRegistries.TERRAIN_MISC_VOID_NOISE_SYSTEM;
            case BIOME -> SystemRegistries.BIOME_MISC_VOID_BIOME_SYSTEM;
            case SURFACE -> SystemRegistries.SURFACE_MISC_VOID_SURFACE_SYSTEM;
            case CARVER -> SystemRegistries.CARVER_MISC_VOID_CARVER_SYSTEM;
            case DECORATION -> SystemRegistries.DECORATION_MISC_VOID_DECORATION_SYSTEM;
        };
    }

    /** 默认选择带一个 seed 参数：五族的 vanilla 实现都读它，缺参数会在 level 构造时抛。 */
    private static SystemSelection vanillaSelection(SystemId id) {
        return new SystemSelection(id.value(), Map.of("seed", Arg.ofLong(0L)));
    }

    /** 非原版维度与缺失维度的默认：五族全 VOID。 */
    private static LevelSelection voidSelection() {
        return new LevelSelection(
                new SystemSelection(SystemRegistries.TERRAIN_MISC_VOID_NOISE_SYSTEM.value(), Map.of()),
                new SystemSelection(SystemRegistries.BIOME_MISC_VOID_BIOME_SYSTEM.value(), Map.of()),
                new SystemSelection(SystemRegistries.SURFACE_MISC_VOID_SURFACE_SYSTEM.value(), Map.of()),
                new SystemSelection(SystemRegistries.CARVER_MISC_VOID_CARVER_SYSTEM.value(), Map.of()),
                new SystemSelection(SystemRegistries.DECORATION_MISC_VOID_DECORATION_SYSTEM.value(), Map.of()));
    }

    public static TerrainSystem terrain(LevelSelection selection) {
        return SystemRegistries.terrain(systemId(selection.terrain()),
                args(selection.terrain(), SystemRegistries::terrainClassOf));
    }

    public static BiomeSystem biome(LevelSelection selection) {
        return SystemRegistries.biome(systemId(selection.biome()),
                args(selection.biome(), SystemRegistries::biomeClassOf));
    }

    public static SurfaceSystem surface(LevelSelection selection) {
        return SystemRegistries.surface(systemId(selection.surface()),
                args(selection.surface(), SystemRegistries::surfaceClassOf));
    }

    public static CarverSystem carver(LevelSelection selection) {
        return SystemRegistries.carver(systemId(selection.carver()),
                args(selection.carver(), SystemRegistries::carverClassOf));
    }

    public static DecorationSystem decoration(LevelSelection selection) {
        return SystemRegistries.decoration(systemId(selection.decoration()),
                args(selection.decoration(), SystemRegistries::decorationClassOf));
    }

    private static SystemId systemId(SystemSelection selection) {
        return new SystemId(selection.id());
    }

    /**
     * 造参数视图，先跑该系统的声明级校验。
     *
     * <p>手改过的 systems.dat 可能违反跨字段规则，而那些判据原先只写在构造器里，症状是世界加载期一份栈顶在
     * 反射上的崩溃。这里拦下之后消息能指到参数，用的还是创建世界页签那一份声明。
     */
    private static SystemArgs args(SystemSelection selection, Function<SystemId, Class<?>> classOf) {
        SystemId id = systemId(selection);
        List<SystemParams.Violation> violations = SystemParams.declaredBy(classOf.apply(id))
                .validate(selection.args());
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "farlands: invalid params for system " + id + ": " + describe(violations));
        }
        return SystemArgs.of(selection.args());
    }

    /**
     * 违规的定位串：键名加理由标识。
     *
     * <p>键名按 TreeSet 排序去重，同一份配置每次产出同一行，日志可 grep。理由只取语言键，不取
     * {@code getArgs()}：参数里可能有 {@code Component}，渲染它们等于把客户端语言拉回服务端消息。
     */
    private static String describe(List<SystemParams.Violation> violations) {
        StringBuilder sb = new StringBuilder();
        for (SystemParams.Violation violation : violations) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            Set<String> keys = new TreeSet<>(violation.keys());
            sb.append(keys.isEmpty() ? "-" : String.join(",", keys));
            sb.append(" [").append(reasonOf(violation.message())).append(']');
        }
        return sb.toString();
    }

    /**
     * 理由标识：translatable 取语言键名，其余取渲染文本。
     *
     * <p>本模组内置的检查与规则一律用 translatable，因此本条产出的消息恒为 ASCII；第三方用 literal 时原样带入
     * 它自己的文本。
     */
    private static String reasonOf(Component message) {
        return message.getContents() instanceof TranslatableContents translatable ? translatable.getKey()
                : message.getString();
    }
}
