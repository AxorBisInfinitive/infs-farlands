package com.inf.farlands.simulation.serialize;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.inf.farlands.simulation.model.SimulationData;
import com.inf.farlands.simulation.registry.SimulationRegistries;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SelectionFile;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * 模拟层选择档案 {@code simulation.dat} 的读写与落地，两族。
 *
 * <p>
 * 骨架全在 {@link SelectionFile}：严格自读、已初始化却缺档即抛、缺档取暂存再取默认、按 server 解析一次。
 * 本类只提供这一层的编码、两族的默认选择与「还原成界面文本」那一层校验，与 {@code SystemsIO} 逐条对应。
 *
 * <p>
 * 两族在界面上按 {@link FamilyKind} 索引：噪声那一族沿用 terrain 的 {@code TERRAIN} 标签，群系那一族用
 * {@code BIOME}。这不是语义借用，那个枚举承担的正是「维度页下第几行」这一个含义，模拟层的两族各占一行。
 *
 * <p>
 * 缺档案的语义与 farlands 相同。模拟层是新增档案，所以带 ssa、没有本档案的旧世界加载即抛；那是「选择是必需
 * 档案」的必然结果，不是缺陷。两族改成必填字段之后，只有一族的那批档案同样落进这一条。
 */
public final class SimulationIO {

    private static final SelectionFile<SimulationData> FILE =
            new SelectionFile<>(SimulationData.TYPE, SimulationData.CODEC);

    private SimulationIO() {
    }

    public static Path file(Path worldRoot) {
        return FILE.file(worldRoot);
    }

    public static Path file(MinecraftServer server) {
        return FILE.file(server);
    }

    /** 严格自读。 */
    public static SimulationData read(Path file) {
        return FILE.read(file);
    }

    /**
     * 该世界根目录下的档案能不能用。第二层逐族、逐维度把每一条选择还原成文本，未登记的系统与类型不符在那里暴露。
     */
    public static boolean compatible(Path worldRoot) {
        return FILE.compatible(worldRoot, data -> {
            for (SimulationData.LevelSelection selection : data.levels().values()) {
                texts(FamilyKind.TERRAIN, selection.noise());
                texts(FamilyKind.BIOME, selection.biome());
            }
        });
    }

    /** 把一份落盘选择还原成界面文本。失败一律抛，由调用方决定判成不兼容还是中止。 */
    public static Map<String, String> texts(FamilyKind kind, SystemsData.SystemSelection selection) {
        SystemId id = new SystemId(selection.id());
        SystemParams declared = SystemParams.declaredBy(classOf(kind, id));
        Map<String, String> out = new LinkedHashMap<>();
        for (SystemParamSpec spec : declared.entries()) {
            SystemsData.Arg arg = selection.args().get(spec.key());
            if (arg != null) {
                out.put(spec.key(), SystemParamSpec.text(spec.key(), spec.type(), arg.value()));
            }
        }
        return out;
    }

    /** 按族取实现类。未注册即抛，是兼容性判据第二层的另一半。 */
    public static Class<?> classOf(FamilyKind kind, SystemId id) {
        return switch (kind) {
            case TERRAIN -> SimulationRegistries.classOf(id);
            case BIOME -> SimulationRegistries.biomeClassOf(id);
            default -> throw new IllegalStateException(
                    "farlands: simulation layer has no family " + kind);
        };
    }

    /** 该族的默认：模拟层没有维度原生系统，一律 VOID。判据只有这一处。 */
    public static SystemId defaultId(FamilyKind kind) {
        return switch (kind) {
            case TERRAIN -> SimulationRegistries.NOISE_VOID;
            case BIOME -> SimulationRegistries.BIOME_VOID;
            default -> throw new IllegalStateException(
                    "farlands: simulation layer has no family " + kind);
        };
    }

    /** 新世界的默认档案：注册表里的全部维度都用两族 VOID。 */
    public static SimulationData defaultFor(MinecraftServer server) {
        Map<Identifier, SimulationData.LevelSelection> levels = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : server.registryAccess()
                .lookupOrThrow(Registries.LEVEL_STEM).entrySet()) {
            levels.put(entry.getKey().identifier(), voidSelection());
        }
        return new SimulationData(levels);
    }

    /** 该维度的选择，缺失补两族 VOID 并照常返回。缺失是正常情形：维度集合由数据包与模组决定。 */
    public static SimulationData.LevelSelection selection(SimulationData data, Identifier dimension) {
        SimulationData.LevelSelection selection = data.selection(dimension);
        return selection != null ? selection : voidSelection();
    }

    /**
     * 造参数视图，先跑声明级校验。
     *
     * <p>手改过的档案可能违反某条跨字段规则，拦下之后消息能指到参数，用的还是创建世界页签那一份声明。
     */
    public static SystemArgs args(FamilyKind kind, SystemsData.SystemSelection selection) {
        SystemId id = new SystemId(selection.id());
        List<SystemParams.Violation> violations = SystemParams.declaredBy(classOf(kind, id))
                .validate(selection.args());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("farlands: invalid simulation params for system " + id);
        }
        return SystemArgs.of(selection.args());
    }

    public static void stageForLevelCreation(SimulationData data) {
        FILE.stageForLevelCreation(data);
    }

    public static SimulationData systemsFor(MinecraftServer server) {
        return FILE.systemsFor(server, SimulationIO::defaultFor);
    }

    public static void clearWorldState() {
        FILE.clearWorldState();
    }

    /** 非原版维度与缺失维度的默认：两族全 VOID。 */
    private static SimulationData.LevelSelection voidSelection() {
        return new SimulationData.LevelSelection(
                new SystemsData.SystemSelection(SimulationRegistries.NOISE_VOID.value(), Map.of()),
                new SystemsData.SystemSelection(SimulationRegistries.BIOME_VOID.value(), Map.of()));
    }
}
