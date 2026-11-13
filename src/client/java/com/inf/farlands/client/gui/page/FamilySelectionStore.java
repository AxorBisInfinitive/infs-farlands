package com.inf.farlands.client.gui.page;

import java.util.Map;

import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;

import net.minecraft.resources.Identifier;

/**
 * 一个页签族的读写入口：维度页与族为键，读写选中的系统与它的参数文本。
 *
 * <p>
 * 抽出来是因为两个页签的差别只有「存在哪里」：创建世界页签存在 {@code CreatingWorldSystemsConfig}，测试页签
 * 存在内存里。其余读法写法完全一致，所以由实现决定落点，调用方不分支。
 */
public interface FamilySelectionStore {

    /** 该维度该族选中的系统，没选过返回 null。 */
    SystemId system(Identifier page, FamilyKind kind);

    void select(Identifier page, FamilyKind kind, SystemId system);

    /** 该系统已填的参数文本，没有条目时返回空映射。 */
    Map<String, String> text(Identifier page, FamilyKind kind, SystemId system);

    void putText(Identifier page, FamilyKind kind, SystemId system, String key, String value);
}
