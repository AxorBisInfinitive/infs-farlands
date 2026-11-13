package com.inf.farlands.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.inf.farlands.client.gui.page.FamilySelectionStore;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemSelectionParser;
import com.inf.farlands.terrain.registry.SystemsData;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 创建世界页里一层可编辑选择的暂存：维度页到族到选中的系统，再到该系统每个参数的文本。
 *
 * <p>
 * farlands 与模拟层各持一个实例，簿记只有这一份。分层靠实例区分，不靠子类：两层的差别只有「有哪些族」与
 * 「按族怎么取实现类」「没选过时用哪个默认」，那三件由调用方作为参数传入，本类不认识具体是哪一层。
 *
 * <p>
 * 校验不在这里发明判据，只把 {@link SystemSelectionParser#violations} 按族逐个调用并汇总。界面的标红与提交前
 * 的拦截因此共用同一份判断，不会分叉。
 */
public final class CreatingWorldSelections implements FamilySelectionStore {

    /** 本层本次创建流程要处理哪些维度，决定界面有几页。由页签构造时喂入。 */
    private volatile List<Identifier> dimensions = List.of();

    /** 维度页到族到选中的系统。 */
    private final Map<Identifier, Map<FamilyKind, SystemId>> systems = new ConcurrentHashMap<>();

    /** 维度页到族到系统到参数名到文本。按系统分组，切换系统后原来填的文本仍留着。 */
    private final Map<Identifier, Map<FamilyKind, Map<SystemId, Map<String, String>>>> texts =
            new ConcurrentHashMap<>();

    public void setDimensions(List<Identifier> order) {
        this.dimensions = List.copyOf(order);
    }

    public List<Identifier> dimensions() {
        return this.dimensions;
    }

    /** 清空全部暂存。打开创建世界页、以及重建世界读盘前各调用一次。 */
    public void reset() {
        this.systems.clear();
        this.texts.clear();
    }

    @Override
    public SystemId system(Identifier page, FamilyKind kind) {
        Map<FamilyKind, SystemId> families = this.systems.get(page);
        return families == null ? null : families.get(kind);
    }

    @Override
    public void select(Identifier page, FamilyKind kind, SystemId system) {
        this.systems.computeIfAbsent(page, k -> new ConcurrentHashMap<>()).put(kind, system);
    }

    @Override
    public Map<String, String> text(Identifier page, FamilyKind kind, SystemId system) {
        Map<FamilyKind, Map<SystemId, Map<String, String>>> families = this.texts.get(page);
        if (families == null) {
            return Map.of();
        }
        Map<SystemId, Map<String, String>> bySystem = families.get(kind);
        if (bySystem == null) {
            return Map.of();
        }
        Map<String, String> held = bySystem.get(system);
        return held == null ? Map.of() : held;
    }

    @Override
    public void putText(Identifier page, FamilyKind kind, SystemId system, String key, String value) {
        this.texts.computeIfAbsent(page, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(kind, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(system, k -> new ConcurrentHashMap<>())
                .put(key, value);
    }

    /** 一条违规所属的维度页、族、系统与键，界面据此定位到该标红的行。 */
    public record Violation(Identifier dimension, FamilyKind family, SystemId system, Set<String> keys,
            Component message) {
    }

    /** 该维度页该族当前的选择，没选过时取 {@code fallback} 给的那个。 */
    public SystemId current(Identifier page, FamilyKind kind, BiFunction<Identifier, FamilyKind, SystemId> fallback) {
        SystemId id = this.system(page, kind);
        return id != null ? id : fallback.apply(page, kind);
    }

    /** 该维度页该族的全部违规，判据来自该系统的参数声明。 */
    public List<SystemParams.Violation> violations(Identifier page, FamilyKind kind,
            Function<SystemId, Class<?>> classOf, BiFunction<Identifier, FamilyKind, SystemId> fallback) {
        SystemId id = this.current(page, kind, fallback);
        return SystemSelectionParser.violations(classOf.apply(id), this.text(page, kind, id));
    }

    /** 该维度页该族编好的落盘选择。文本非法时抛，由调用方中止创建。 */
    public SystemsData.SystemSelection selection(Identifier page, FamilyKind kind,
            Function<SystemId, Class<?>> classOf, BiFunction<Identifier, FamilyKind, SystemId> fallback) {
        SystemId id = this.current(page, kind, fallback);
        return SystemSelectionParser.parse(id, this.text(page, kind, id), classOf.apply(id));
    }

    /**
     * 本层全部维度页、全部给定族的违规。
     *
     * <p>{@code classOf} 与 {@code fallback} 各按族取一次，两层因此共用同一段汇总逻辑，各自只提供自己的表。
     */
    public List<Violation> violationsAll(Function<FamilyKind, Function<SystemId, Class<?>>> classOf,
            List<FamilyKind> kinds, BiFunction<Identifier, FamilyKind, SystemId> fallback) {
        List<Violation> out = new ArrayList<>();
        for (Identifier page : this.dimensions) {
            for (FamilyKind kind : kinds) {
                SystemId id = this.current(page, kind, fallback);
                List<SystemParams.Violation> found;
                try {
                    found = SystemSelectionParser.violations(classOf.apply(kind).apply(id),
                            this.text(page, kind, id));
                } catch (RuntimeException e) {
                    out.add(new Violation(page, kind, id, Set.of(), SystemSelectionParser.problem(e)));
                    continue;
                }
                for (SystemParams.Violation violation : found) {
                    out.add(new Violation(page, kind, id, violation.keys(), violation.message()));
                }
            }
        }
        return out;
    }

    /** 预填一条选择与它的参数文本，供重建世界那条路使用。 */
    public void put(Identifier page, FamilyKind kind, SystemId id, Map<String, String> saved) {
        this.select(page, kind, id);
        saved.forEach((key, value) -> this.putText(page, kind, id, key, value));
    }
}
