package com.inf.farlands.client.gui.page;

import java.util.HashMap;
import java.util.Map;

import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;

import net.minecraft.resources.Identifier;

/**
 * 只活在内存里的族选择暂存，给不进落盘的那类页签用。
 *
 * <p>
 * 界面只在客户端主线程读写，所以用普通 {@link HashMap}。与 {@code CreatingWorldSystemsConfig} 的区别只有
 * 生命周期：这里的条目随实例走，界面重建就没了，正合测试页签的用途。
 */
public final class InMemorySelectionStore implements FamilySelectionStore {

    private final Map<Identifier, Map<FamilyKind, SystemId>> systems = new HashMap<>();

    private final Map<Identifier, Map<FamilyKind, Map<SystemId, Map<String, String>>>> texts = new HashMap<>();

    @Override
    public SystemId system(Identifier page, FamilyKind kind) {
        Map<FamilyKind, SystemId> families = this.systems.get(page);
        return families == null ? null : families.get(kind);
    }

    @Override
    public void select(Identifier page, FamilyKind kind, SystemId system) {
        this.systems.computeIfAbsent(page, k -> new HashMap<>()).put(kind, system);
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
        this.texts.computeIfAbsent(page, k -> new HashMap<>())
                .computeIfAbsent(kind, k -> new HashMap<>())
                .computeIfAbsent(system, k -> new HashMap<>())
                .put(key, value);
    }
}
