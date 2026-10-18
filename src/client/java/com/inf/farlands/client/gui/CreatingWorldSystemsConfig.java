package com.inf.farlands.client.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemRegistries;
import com.inf.farlands.terrain.registry.SystemSelectionParser;
import com.inf.farlands.terrain.registry.SystemsData;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 创建世界页签下面全部可编辑状态的暂存处：每个维度页 × 五族各自选中的系统，以及该系统每个参数的文本。
 *
 * <p>
 * 放静态持有者而不是页签实例上，是因为页签在 CreateWorldScreen.init 里构造，界面重建会重建页签，
 * 实例字段存不住。
 *
 * <p>
 * 维度页的集合不写死：它由世界实际拥有的维度决定，在本类里存一份，界面建页与落盘编 SystemsData
 * 读的是同一份。两处若各取各的，界面上改了而落盘的维度集合不含它，或反过来，都会静默丢配置。
 *
 * <p>
 * 本类依赖 SystemToast 与 Minecraft，故留在 client 源集；维度键与族枚举在 main 源集。
 *
 * <p>
 * 两个入口预填的东西不同：新建与测试世界从空开始，界面走各族默认；重建已有世界按该世界的
 * systems.dat 预填，缺项与读取失败都退回默认，读取失败另弹一个 toast。
 */
public final class CreatingWorldSystemsConfig {

    /** 没有参数声明的系统退化成自由文本框，它的文本用这个键存，与参数名不可能冲突。 */
    public static final String FREE_KEY = "";

    /** 本世界实际拥有的维度，决定界面有几页。由页签构造时喂入。 */
    private static volatile List<Identifier> dimensions = List.of();

    /** 维度 -> 族 -> 选中的系统。 */
    private static final Map<Identifier, Map<FamilyKind, SystemId>> SYSTEMS = new ConcurrentHashMap<>();

    /** 维度 -> 族 -> 系统 -> 参数名 -> 文本。按系统分组，切换系统后原来填的文本仍留着。 */
    private static final Map<Identifier, Map<FamilyKind, Map<SystemId, Map<String, String>>>> TEXT = new ConcurrentHashMap<>();

    private CreatingWorldSystemsConfig() {
    }

    /**
     * 登记这次创建流程要处理哪些维度。界面页列表与落盘内容都以它为准，所以由页签构造时统一喂一次。
     */
    public static void setDimensions(List<Identifier> order) {
        dimensions = List.copyOf(order);
    }

    /** 本次创建流程的维度，保序。 */
    public static List<Identifier> dimensions() {
        return dimensions;
    }

    /** 清空全部暂存。打开创建世界页、以及重建世界读盘前各调用一次。 */
    public static void reset() {
        SYSTEMS.clear();
        TEXT.clear();
    }

    /** 该维度该族选中的系统，没选过返回 null。 */
    public static SystemId system(Identifier page, FamilyKind kind) {
        return inner(SYSTEMS, page).get(kind);
    }

    public static void select(Identifier page, FamilyKind kind, SystemId system) {
        inner(SYSTEMS, page).put(kind, system);
    }

    /** 该维度该族该系统的参数文本。没有条目时返回空视图，调用方不必判空。 */
    public static Map<String, String> text(Identifier page, FamilyKind kind, SystemId system) {
        Map<SystemId, Map<String, String>> bySystem = innerText(page, kind);
        Map<String, String> held = bySystem.get(system);
        return held == null ? Map.of() : held;
    }

    /** 写一条参数文本。只在真的有编辑时调用，空框不落条目。 */
    public static void putText(Identifier page, FamilyKind kind, SystemId system, String key, String value) {
        innerText(page, kind).computeIfAbsent(system, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    private static <K, V> Map<K, V> inner(Map<Identifier, Map<K, V>> outer, Identifier page) {
        return outer.computeIfAbsent(page, k -> new ConcurrentHashMap<>());
    }

    /** TEXT 比 SYSTEMS 多一层，单独写一个重载，免得泛型推断失败。 */
    private static Map<SystemId, Map<String, String>> innerText(Identifier page, FamilyKind kind) {
        return inner(TEXT, page).computeIfAbsent(kind, k -> new ConcurrentHashMap<>());
    }

    /**
     * 按已有世界的 systems.dat 预填。文件不存在或读取失败时，映射留空，界面退回默认；读取失败另弹
     * 一个 toast。
     *
     * <p>
     * 不做维度过滤：文件里有哪个维度就填哪个维度，界面按世界实际的维度取用。
     *
     * @param worldRoot 世界根目录，由 LevelStorageAccess.getLevelPath(LevelResource.ROOT)
     *                  给出
     */
    public static void prefillFrom(Path worldRoot) {
        Path file = SystemsData.ID.withSuffix(".dat")
                .resolveAgainst(worldRoot.resolve(LevelResource.DATA.id()));
        try {
            if (Files.exists(file)) {
                apply(SystemsIO.read(file));
            }
        } catch (RuntimeException e) {
            toastReadFailure(e);
        }
    }

    /**
     * 把当前全部维度页的选择与参数文本编成一份 {@link SystemsData}，供新建世界落盘。
     *
     * <p>
     * 五个族的注册表取类与参数文本的键都在这里收口，{@link SystemSelectionParser} 只做文本到
     * {@code Arg} 的还原。选中的系统不在该族注册表里时 {@code classOf} 抛。
     *
     * <p>
     * 文本非法的解析失败直穿给调用方，由它决定中止创建，不在此处吞掉或退回默认。
     */
    public static SystemsData buildSystemsData() {
        Map<Identifier, SystemsData.LevelSelection> levels = new LinkedHashMap<>();
        for (Identifier page : dimensions) {
            levels.put(page, levelSelection(page));
        }
        return new SystemsData(levels);
    }

    /**
     * 当前界面的选择与参数能否编成一份配置：能则 null，不能则返回可显示的错因。创建按钮提交前用它
     * 校验，判据与真正落盘那一次完全相同，只是多吞一次异常。
     */
    public static String problem() {
        try {
            buildSystemsData();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /**
     * 一页的选择。五族的默认都按维度分派：原版三个维度各取各的 vanilla，其余维度退 VOID。
     *
     * <p>判据取自 {@link SystemsIO#defaultId}，与 {@code SystemsIO.defaultFor} 同源。新世界的维度集合
     * 来自界面暂存，那条路在新世界走不到，所以这份默认就是新世界实际拿到的默认；两处若各写一份判据，
     * 默认就会分叉。
     */
    private static SystemsData.LevelSelection levelSelection(Identifier page) {
        return new SystemsData.LevelSelection(
                family(page, FamilyKind.TERRAIN, SystemRegistries::terrainClassOf,
                        SystemsIO.defaultId(page, FamilyKind.TERRAIN)),
                family(page, FamilyKind.BIOME, SystemRegistries::biomeClassOf,
                        SystemsIO.defaultId(page, FamilyKind.BIOME)),
                family(page, FamilyKind.SURFACE, SystemRegistries::surfaceClassOf,
                        SystemsIO.defaultId(page, FamilyKind.SURFACE)),
                family(page, FamilyKind.CARVER, SystemRegistries::carverClassOf,
                        SystemsIO.defaultId(page, FamilyKind.CARVER)),
                family(page, FamilyKind.DECORATION, SystemRegistries::decorationClassOf,
                        SystemsIO.defaultId(page, FamilyKind.DECORATION)));
    }

    /** 一族的选择。没选过时用该族的 VOID，与界面的初始选中同一份常量。 */
    private static SystemsData.SystemSelection family(Identifier page, FamilyKind kind,
            Function<SystemId, Class<?>> classOf, SystemId fallback) {
        SystemId id = system(page, kind);
        if (id == null) {
            id = fallback;
        }
        return SystemSelectionParser.parse(id, text(page, kind, id), classOf.apply(id));
    }

    private static void apply(SystemsData data) {
        for (Map.Entry<Identifier, SystemsData.LevelSelection> level : data.levels().entrySet()) {
            applyLevel(level.getKey(), level.getValue());
        }
    }

    private static void applyLevel(Identifier page, SystemsData.LevelSelection selection) {
        applySystem(page, FamilyKind.TERRAIN, selection.terrain());
        applySystem(page, FamilyKind.BIOME, selection.biome());
        applySystem(page, FamilyKind.SURFACE, selection.surface());
        applySystem(page, FamilyKind.CARVER, selection.carver());
        applySystem(page, FamilyKind.DECORATION, selection.decoration());
    }

    /**
     * 把一个族的选择预填进界面：有参数声明的按声明键逐个写文本形态，没有声明的把整张参数复合写进
     * 自由框那个键。两侧各自与解析端同一形态，重建世界时才能原样读回。
     */
    private static void applySystem(Identifier page, FamilyKind kind, SystemsData.SystemSelection selection) {
        SystemId id = new SystemId(selection.id());
        select(page, kind, id);
        SystemParams declared = SystemParams.declaredBy(classOf(kind, id));
        if (declared == null) {
            putText(page, kind, id, FREE_KEY, SystemsData.SystemSelection.ARGS_CODEC
                    .encodeStart(NbtOps.INSTANCE, selection.args())
                    .getOrThrow()
                    .toString());
            return;
        }
        for (SystemParamSpec spec : declared.entries()) {
            SystemsData.Arg arg = selection.args().get(spec.key());
            if (arg != null) {
                putText(page, kind, id, spec.key(),
                        SystemParamSpec.text(spec.key(), spec.type(), arg.value()));
            }
        }
    }

    /** 按族取实现类，供预填判断该系统有没有参数声明。 */
    private static Class<?> classOf(FamilyKind kind, SystemId id) {
        return switch (kind) {
            case TERRAIN -> SystemRegistries.terrainClassOf(id);
            case BIOME -> SystemRegistries.biomeClassOf(id);
            case SURFACE -> SystemRegistries.surfaceClassOf(id);
            case CARVER -> SystemRegistries.carverClassOf(id);
            case DECORATION -> SystemRegistries.decorationClassOf(id);
        };
    }

    /** 读取失败弹一段中英可译的提示，正文带异常消息便于定位。 */
    private static void toastReadFailure(RuntimeException e) {
        Minecraft minecraft = Minecraft.getInstance();
        SystemToast.add(minecraft.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("createWorld.tab.infs-farlands.error.read.title"),
                Component.translatable("createWorld.tab.infs-farlands.error.read.message",
                        String.valueOf(e.getMessage())));
    }
}
