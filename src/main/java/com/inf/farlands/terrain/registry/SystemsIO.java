package com.inf.farlands.terrain.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
 * 读自己来，不走 SavedDataStorage：它的读路径把解析失败记成日志后返回 null，上层
 * computeIfAbsent 随即新建默认对象，损坏的配置因此不会失败，而这条路要的是一律抛出。
 *
 * <p>
 * 写借 SavedDataStorage：set 只标脏进缓存，由 MinecraftServer.saveAllChunks 按既有的保存节奏
 * 落盘，编码用同一份 Codec，两端格式不会走偏。配置文件在磁盘上不存在而世界又已初始化时即抛；
 * 只有新世界的首次解析才写默认值。
 */
public final class SystemsIO {

    private SystemsIO() {
    }

    /** 配置文件路径，与 SavedDataStorage 的取法同式。 */
    public static Path file(MinecraftServer server) {
        return SystemsData.ID.withSuffix(".dat")
                .resolveAgainst(server.getWorldPath(LevelResource.DATA));
    }

    /**
     * 创建世界页签的选择在 level 构造之前的暂存。客户端在服务端线程启动之前放进来，本类在文件
     * 不存在且世界尚未初始化的那条分支里取用一次即清空，所以既成世界与专服都碰不到它。
     *
     * <p>只在「这次 level 构造」的生命周期内有效，不进磁盘、不做回退。取用时落盘仍由
     * {@link net.minecraft.world.level.storage.SavedDataStorage} 负责，本类不写文件。
     */
    private static volatile SystemsData staged;

    /** level 构造前把界面选择交给本类。由集成服务器在 createLevels 之前调用。 */
    public static void stageForLevelCreation(SystemsData data) {
        staged = data;
    }

    private static SystemsData takeStaged() {
        SystemsData data = staged;
        staged = null;
        return data;
    }

    /**
     * 该 server 的系统配置，每个 server 实例解析一次。该 server 的第一个构造的 level 走到这里时
     * {@code resolve} 尚未跑过，解析并记下；其余维度构造时直接命中。
     *
     * <p>用 server 实例做键而不是「主世界是否已构造」，后者依赖 level 的构造顺序，且在世界没有原版
     * 主世界时判不出来。WeakHashMap 是零成本的兜底：server 正常关闭时本就该丢弃。
     */
    private static final Map<MinecraftServer, SystemsData> RESOLVED =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static SystemsData systemsFor(MinecraftServer server) {
        synchronized (RESOLVED) {
            SystemsData data = RESOLVED.get(server);
            if (data == null) {
                data = resolve(server);
                RESOLVED.put(server, data);
            }
            return data;
        }
    }

    /** 解析该世界的配置：文件在则严格自读，不在则按新世界落默认并写回，其余情形抛。 */
    public static SystemsData resolve(MinecraftServer server) {
        Path file = file(server);
        if (Files.exists(file)) {
            return read(file);
        }
        if (server.getWorldData().overworldData().isInitialized()) {
            throw new IllegalStateException("farlands: existing world has no systems config file " + file);
        }
        SystemsData data = takeStaged();
        if (data == null) {
            data = defaultFor(server);
        }
        server.getDataStorage().set(SystemsData.TYPE, data);
        return data;
    }

    /** 严格自读。任何异常都带上文件路径直穿，不做回退也不新建默认。 */
    public static SystemsData read(Path file) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.defaultQuota());
        } catch (Exception e) {
            throw new IllegalStateException("farlands: failed to read systems config " + file, e);
        }
        Tag data = root.get("data");
        if (data == null) {
            throw new IllegalStateException("farlands: systems config has no data tag " + file);
        }
        try {
            return SystemsData.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow(IllegalStateException::new);
        } catch (RuntimeException e) {
            throw new IllegalStateException("farlands: failed to parse systems config " + file, e);
        }
    }

    /**
     * 该维度的选择。缺失时补该族 VOID 并照常返回，不抛。
     *
     * <p>缺失是正常情形而非错误：世界维度由数据包与模组决定，配置文件写在某个时刻，之后维度集合
     * 可能变。按既定语义，非原版维度的默认选择是 VOID，所以缺失等价于「没为它配过」，补上即可。
     */
    public static LevelSelection selection(SystemsData data, Identifier dimension) {
        LevelSelection selection = data.selection(dimension);
        return selection != null ? selection : voidSelection();
    }

    /**
     * 内置默认。原版三个维度各取各的 vanilla 五族，其余维度取 {@link #voidSelection()}。
     * 维度取注册表里的全部 LevelStem，新世界因此不会因为数据包加了维度而缺条目。只在该世界还没有
     * 配置文件时生效，已有配置文件的世界一律以文件为准。
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
     * <p>判据只有这一处。新世界的维度集合来自界面暂存，{@link #defaultFor} 那条路在新世界走不到，
     * 所以界面建页与落盘默认都经 {@link #defaultId} 取同一份判据，两处各写一份会分叉。
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
     * <p>
     * 手改过的 systems.dat 可能违反某条跨字段规则，而那些判据原先只写在构造器里，症状是世界加载期一份
     * 栈顶在反射上的崩溃。这里拦下之后消息能指到参数，用的还是创建世界页签那一份声明。
     */
    private static SystemArgs args(SystemSelection selection, Function<SystemId, Class<?>> classOf) {
        SystemId id = systemId(selection);
        SystemParams params = SystemParams.declaredBy(classOf.apply(id));
        if (params != null) {
            List<SystemParams.Violation> violations = params.validate(selection.args());
            if (!violations.isEmpty()) {
                throw new IllegalStateException("farlands: system " + id + " 参数不合规: " + describe(violations));
            }
        }
        return SystemArgs.of(selection.args());
    }

    private static String describe(List<SystemParams.Violation> violations) {
        StringBuilder sb = new StringBuilder();
        for (SystemParams.Violation violation : violations) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(violation.message().getString());
        }
        return sb.toString();
    }
}
