package com.inf.farlands.client.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.inf.farlands.client.gui.page.FamilySelectionStore;
import com.inf.farlands.simulation.model.SimulationData;
import com.inf.farlands.simulation.serialize.SimulationIO;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemsData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 创建世界页里模拟层那一层的可编辑暂存：每个维度页两族的选择与参数文本。
 *
 * <p>
 * 簿记与校验都在 {@link CreatingWorldSelections}，与 farlands 那一层共用同一份实现。本类只提供这一层的族表、
 * 按族取实现类的办法，即模拟注册表的两张表，默认项一律 VOID，以及落盘结构 {@link SimulationData}。
 *
 * <p>
 * 族表顺序是噪声在前、群系在后：噪声是这一层原有的一行，加族不改它的位置。
 */
public final class CreatingWorldSimulationConfig {

    private static final List<FamilyKind> FAMILIES = List.of(FamilyKind.TERRAIN, FamilyKind.BIOME);

    /** 本层的暂存。 */
    private static final CreatingWorldSelections STATE = new CreatingWorldSelections();

    /** 页签用的单例。 */
    public static final FamilySelectionStore STORE = STATE;

    /** 该族的默认：模拟层没有维度原生系统，一律 VOID。 */
    private static final BiFunction<Identifier, FamilyKind, SystemId> DEFAULT =
            (page, kind) -> SimulationIO.defaultId(kind);

    /** 模拟层按族取实现类的办法，与 farlands 那边的 {@code classOf(kind)} 同形。 */
    private static final Function<FamilyKind, Function<SystemId, Class<?>>> CLASS_OF =
            kind -> id -> SimulationIO.classOf(kind, id);

    private CreatingWorldSimulationConfig() {
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

    /** 本层的族表，供界面建行。 */
    public static List<FamilyKind> families() {
        return FAMILIES;
    }

    /** 当前界面的全部违规。与 {@link #buildSimulationData} 用同一份判据。 */
    public static List<CreatingWorldSelections.Violation> violations() {
        return STATE.violationsAll(CLASS_OF, FAMILIES, DEFAULT);
    }

    /** 编成落盘结构，供新建世界写入 {@code simulation.dat}。文本非法时抛，由调用方中止创建。 */
    public static SimulationData buildSimulationData() {
        if (!violations().isEmpty()) {
            throw new IllegalStateException("farlands: invalid simulation params at world creation");
        }
        Map<Identifier, SimulationData.LevelSelection> levels = new LinkedHashMap<>();
        for (Identifier page : STATE.dimensions()) {
            levels.put(page, new SimulationData.LevelSelection(
                    STATE.selection(page, FamilyKind.TERRAIN, CLASS_OF.apply(FamilyKind.TERRAIN), DEFAULT),
                    STATE.selection(page, FamilyKind.BIOME, CLASS_OF.apply(FamilyKind.BIOME), DEFAULT)));
        }
        return new SimulationData(levels);
    }

    /** 按已有世界的 {@code simulation.dat} 预填。读不出来时留空、界面退回默认，另弹 toast。 */
    public static void prefillFrom(Path worldRoot) {
        Path file = SimulationIO.file(worldRoot);
        try {
            if (Files.exists(file)) {
                apply(SimulationIO.read(file));
            }
        } catch (RuntimeException e) {
            Minecraft minecraft = Minecraft.getInstance();
            SystemToast.add(minecraft.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("createWorld.tab.infs-farlands.error.read.title"),
                    Component.translatable("createWorld.tab.infs-farlands.error.read.message",
                            String.valueOf(e.getMessage())));
        }
    }

    private static void apply(SimulationData data) {
        for (Map.Entry<Identifier, SimulationData.LevelSelection> entry : data.levels().entrySet()) {
            Identifier page = entry.getKey();
            apply(page, FamilyKind.TERRAIN, entry.getValue().terrain());
            apply(page, FamilyKind.BIOME, entry.getValue().biome());
        }
    }

    private static void apply(Identifier page, FamilyKind kind, SystemsData.SystemSelection selection) {
        STATE.put(page, kind, new SystemId(selection.id()), SimulationIO.texts(kind, selection));
    }
}
