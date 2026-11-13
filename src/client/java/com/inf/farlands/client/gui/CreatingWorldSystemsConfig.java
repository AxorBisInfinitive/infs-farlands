package com.inf.farlands.client.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.inf.farlands.client.gui.page.FamilySelectionStore;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemsData;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

/**
 * 创建世界页里 farlands 那一层的可编辑暂存：每个维度页五族的选择与参数文本。
 *
 * <p>
 * 簿记在 {@link CreatingWorldSelections}，本类只提供这一层的族表、按族取实现类的办法、默认项与落盘结构。
 * 两层各持一个容器实例，分层靠实例，不靠两份代码。
 *
 * <p>
 * 静态持有者而不是页签实例：页签在界面重建时会重建，实例字段存不住。
 */
public final class CreatingWorldSystemsConfig {

    /** 五族，界面从上到下的顺序。 */
    private static final List<FamilyKind> FAMILIES = List.of(FamilyKind.BIOME, FamilyKind.TERRAIN,
            FamilyKind.SURFACE, FamilyKind.CARVER, FamilyKind.DECORATION);

    /** 本层的暂存。 */
    private static final CreatingWorldSelections STATE = new CreatingWorldSelections();

    /** 页签用的单例。 */
    public static final FamilySelectionStore STORE = STATE;

    private CreatingWorldSystemsConfig() {
    }

    public static void setDimensions(List<Identifier> order) {
        STATE.setDimensions(order);
    }

    public static List<Identifier> dimensions() {
        return STATE.dimensions();
    }

    /** 清空全部暂存。打开创建世界页、以及重建世界读盘前各调用一次。 */
    public static void reset() {
        STATE.reset();
    }

    public static SystemId system(Identifier page, FamilyKind kind) {
        return STATE.system(page, kind);
    }

    public static void select(Identifier page, FamilyKind kind, SystemId system) {
        STATE.select(page, kind, system);
    }

    public static Map<String, String> text(Identifier page, FamilyKind kind, SystemId system) {
        return STATE.text(page, kind, system);
    }

    public static void putText(Identifier page, FamilyKind kind, SystemId system, String key, String value) {
        STATE.putText(page, kind, system, key, value);
    }

    /** 本层五族，供界面按族建行。 */
    public static List<FamilyKind> families() {
        return FAMILIES;
    }

    /** 当前界面的全部违规，跨全部维度页与五族。与 {@link #buildSystemsData} 用同一份判据。 */
    public static List<CreatingWorldSelections.Violation> violations() {
        return STATE.violationsAll(CreatingWorldSystemsConfig::classOf, FAMILIES, SystemsIO::defaultId);
    }

    /**
     * 把当前暂存编成一份 {@link SystemsData}，供新建世界落盘。
     *
     * <p>文本非法时抛，由调用方中止创建，不在此处吞掉或退回默认。
     */
    public static SystemsData buildSystemsData() {
        List<CreatingWorldSelections.Violation> violations = violations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("farlands: invalid params at world creation: " + describe(violations));
        }
        Map<Identifier, SystemsData.LevelSelection> levels = new LinkedHashMap<>();
        for (Identifier page : STATE.dimensions()) {
            levels.put(page, new SystemsData.LevelSelection(
                    select(page, FamilyKind.TERRAIN),
                    select(page, FamilyKind.BIOME),
                    select(page, FamilyKind.SURFACE),
                    select(page, FamilyKind.CARVER),
                    select(page, FamilyKind.DECORATION)));
        }
        return new SystemsData(levels);
    }

    private static SystemsData.SystemSelection select(Identifier page, FamilyKind kind) {
        return STATE.selection(page, kind, classOf(kind), SystemsIO::defaultId);
    }

    /** 按族取实现类。界面按族重跑校验与编参数都用它。 */
    public static Function<SystemId, Class<?>> classOf(FamilyKind kind) {
        return switch (kind) {
            case BIOME -> com.inf.farlands.terrain.registry.SystemRegistries::biomeClassOf;
            case TERRAIN -> com.inf.farlands.terrain.registry.SystemRegistries::terrainClassOf;
            case SURFACE -> com.inf.farlands.terrain.registry.SystemRegistries::surfaceClassOf;
            case CARVER -> com.inf.farlands.terrain.registry.SystemRegistries::carverClassOf;
            case DECORATION -> com.inf.farlands.terrain.registry.SystemRegistries::decorationClassOf;
        };
    }

    /** 按已有世界的 systems.dat 预填。读不出来时映射留空、界面退回默认，另弹 toast。 */
    public static void prefillFrom(Path worldRoot) {
        Path file = SystemsIO.file(worldRoot);
        try {
            if (Files.exists(file)) {
                apply(SystemsIO.read(file));
            }
        } catch (RuntimeException e) {
            Minecraft minecraft = Minecraft.getInstance();
            SystemToast.add(minecraft.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("createWorld.tab.infs-farlands.error.read.title"),
                    Component.translatable("createWorld.tab.infs-farlands.error.read.message",
                            String.valueOf(e.getMessage())));
        }
    }

    private static void apply(SystemsData data) {
        for (Map.Entry<Identifier, SystemsData.LevelSelection> entry : data.levels().entrySet()) {
            put(entry.getKey(), FamilyKind.TERRAIN, entry.getValue().terrain());
            put(entry.getKey(), FamilyKind.BIOME, entry.getValue().biome());
            put(entry.getKey(), FamilyKind.SURFACE, entry.getValue().surface());
            put(entry.getKey(), FamilyKind.CARVER, entry.getValue().carver());
            put(entry.getKey(), FamilyKind.DECORATION, entry.getValue().decoration());
        }
    }

    /** 一侧的预填：还原在 SystemsIO，与选择世界页的兼容性判据共用同一段，两处不会漂移。 */
    private static void put(Identifier page, FamilyKind kind, SystemsData.SystemSelection selection) {
        STATE.put(page, kind, new SystemId(selection.id()), SystemsIO.texts(kind, selection));
    }

    /** 违规的定位串：键名排序去重，理由只取语言键，日志里可 grep 且不随客户端语言变。 */
    private static String describe(List<CreatingWorldSelections.Violation> violations) {
        StringBuilder sb = new StringBuilder();
        for (CreatingWorldSelections.Violation violation : violations) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            Set<String> keys = new TreeSet<>(violation.keys());
            sb.append(violation.dimension()).append(" / ").append(violation.family().name()).append(" / ")
                    .append(violation.system()).append(": ")
                    .append(keys.isEmpty() ? "-" : String.join(",", keys))
                    .append(" [").append(reasonOf(violation.message())).append(']');
        }
        return sb.toString();
    }

    private static String reasonOf(Component message) {
        return message.getContents() instanceof TranslatableContents translatable ? translatable.getKey()
                : message.getString();
    }
}
