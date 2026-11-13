package com.inf.farlands.client.gui.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.inf.farlands.client.gui.demo.DropdownSelect;
import com.inf.farlands.client.gui.demo.ParamGroup;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemSelectionParser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 一个族在界面上的那一行：标签、下拉、以及按声明生成的参数框。
 *
 * <p>
 * 校验分两层，都挂在本行上。逐键检查由 {@link ParamGroup} 自己做，失败只把该框标红；跨字段规则由这里的
 * {@link #revalidate()} 跑，判据与落盘那一次同源，见 {@link SystemSelectionParser#violations}；失败时把
 * 规则引用的那几个参数一起标红，并回报本行是否非法。
 *
 * <p>
 * 初次预填不在构造器里做：{@link #applySelection()} 由页签在把本行登记进表之后调用，因为预填会触发一次
 * 校验，而校验要能从表里查回本行。
 */
public final class FamilyRow {

    private static final int FIELD_HEIGHT = 20;

    private final Identifier page;
    private final FamilyKind kind;
    private final List<SystemId> ids;
    private final Function<SystemId, Class<?>> classOf;
    private final FamilySelectionStore store;
    private final java.util.function.BiFunction<Identifier, FamilyKind, SystemId> fallback;
    private final ParamGroup group;
    private final StringWidget label;
    private final Component normalLabel;
    private final Component invalidLabel;
    private final Runnable onChanged;
    private final Runnable relayout;
    private final Layout layout;
    private SystemId current;

    public FamilyRow(Identifier page, FamilyKind kind, List<SystemId> ids, Function<SystemId, Class<?>> classOf,
            FamilySelectionStore store, java.util.function.BiFunction<Identifier, FamilyKind, SystemId> fallback,
            DropdownSelect.Layer layer, DropdownSelect.Revealer revealer, int width,
            Font font, Component title, Runnable onChanged, Runnable relayout) {
        this.page = page;
        this.kind = kind;
        this.ids = ids;
        this.classOf = classOf;
        this.store = store;
        this.fallback = fallback;
        this.onChanged = onChanged;
        this.relayout = relayout;
        this.normalLabel = title;
        this.invalidLabel = title.copy().withStyle(ChatFormatting.RED);
        this.label = new StringWidget(title, font);
        this.group = new ParamGroup(width);
        this.group.setOnChanged(onChanged);
        SystemId saved = store.system(page, kind);
        int index = Math.max(0, ids.indexOf(saved == null ? fallback.apply(page, kind) : saved));
        DropdownSelect dropdown = new DropdownSelect(layer, revealer, width, FIELD_HEIGHT, options(ids), index,
                i -> {
                    SystemId chosen = ids.get(i);
                    this.group.reload();
                    this.store.select(page, kind, chosen);
                    this.applySelection(chosen);
                });
        LinearLayout control = LinearLayout.vertical().spacing(4);
        control.addChild(dropdown);
        control.addChild(this.group);
        LinearLayout block = LinearLayout.vertical().spacing(4);
        block.addChild(this.label);
        block.addChild(control);
        this.layout = block;
    }

    /** 本行在页面里的布局块，由页签加进正文。 */
    public Layout layout() {
        return this.layout;
    }

    /**
     * 按当前选中项建参数组：读该系统的声明，预填已存文本，并把后续编辑写回暂存处。
     *
     * <p>页签在登记完本行之后调用一次；下拉换项时也走这里。
     */
    public void applySelection() {
        SystemId saved = this.store.system(this.page, this.kind);
        this.applySelection(saved == null ? this.fallback.apply(this.page, this.kind) : saved);
    }

    private void applySelection(SystemId id) {
        this.current = id;
        this.group.setParams(SystemParams.declaredBy(this.classOf.apply(id)),
                this.store.text(this.page, this.kind, id),
                (key, value) -> this.store.putText(this.page, this.kind, id, key, value));
        this.relayout.run();
    }

    /**
     * 重跑本行的跨字段规则，返回本行是否非法。
     *
     * <p>先清上一轮的规则标红再重算，否则修好一条之后其余的会一直红着。逐键错因不受影响。
     */
    public boolean revalidate() {
        this.group.clearRuleMarks();
        SystemId id = this.current == null ? this.fallback.apply(this.page, this.kind) : this.current;
        List<SystemParams.Violation> found = SystemSelectionParser.violations(this.classOf.apply(id),
                this.group.currentTexts());
        for (SystemParams.Violation violation : found) {
            this.group.markRule(violation.keys(), violation.message());
        }
        return !found.isEmpty();
    }

    /** 标红或复位本行的族标签。只在颜色上区分，不改文本、不改布局。 */
    public void setLabelInvalid(boolean invalid) {
        this.label.setMessage(invalid ? this.invalidLabel : this.normalLabel);
    }

    /** 当前选中的系统。 */
    public SystemId current() {
        return this.current;
    }

    /** 下拉选项的文字，与选中项显示、量宽度用的都是同一份标题。 */
    private static List<Component> options(List<SystemId> ids) {
        List<Component> out = new ArrayList<>(ids.size());
        for (SystemId id : ids) {
            out.add(DimensionTab.systemTitle(id));
        }
        return out;
    }

    /** 参数文本的只读视图，供页签在需要时取用。 */
    public Map<String, String> currentTexts() {
        return this.group.currentTexts();
    }

    /** 本行当前选中项为键的族。 */
    public FamilyKind kind() {
        return this.kind;
    }

    /** 本行所属的维度页。 */
    public Identifier page() {
        return this.page;
    }

    /** 触发一次变化回调，供页签在切换选中项之后复用同一路径。 */
    void notifyChanged() {
        this.onChanged.run();
    }
}
