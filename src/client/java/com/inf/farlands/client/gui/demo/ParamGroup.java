package com.inf.farlands.client.gui.demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemSelectionParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 按声明生成的一行行参数文本框。
 *
 * <p>自持子控件、自己渲染与分发事件。要自建容器是因为外层 ScrollableLayout 的滚动容器在构造时就把内容
 * 叶子抄进私有列表，之后往内容布局里加控件既不会被画也收不到事件，而参数框数随选中的系统变化。
 *
 * <p>两处针对嵌套的补丁：
 * 一是行坐标。本控件的 x/y 由外层布局决定，装配时未必已定位，所以 setX 与 setY 都要触发一次行重排，
 * 渲染前再排一次兜底。
 * 二是滚轮。基类 AbstractScrollArea.mouseScrolled 恒返回 true，本组从不滚动，会吃掉外层页面的滚轮，
 * 所以直接返回 false。
 *
 * <p>没有声明时退化成单个自由文本框，预填一段示例，空文本等于空映射。类型化框预填声明的默认值文本，
 * 没有字面默认值的参数留空，空框的取值由该参数声明的空关键字决定。
 */
public final class ParamGroup extends AbstractContainerWidget {

    private static final int ROW_HEIGHT = 22;
    private static final int BOX_HEIGHT = 20;
    private static final int LABEL_GAP = 4;
    private static final int MIN_LABEL_WIDTH = 40;
    private static final int MIN_BOX_WIDTH = 80;
    private static final int LABEL_TOP = 6;
    /** 框内文本上限。原版 EditBox 的 maxLength 默认只有 32，贴一段参数 JSON 会被静默截断。 */
    private static final int MAX_TEXT_LENGTH = 1024;
    /** 文本不合声明时的字色，合法时换回 EditBox.DEFAULT_TEXT_COLOR。 */
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;

    private final List<AbstractWidget> children = new ArrayList<>();
    /** 参数名到它的框。切换系统前用它把当前文本整体刷一遍。 */
    private final Map<String, EditBox> texts = new LinkedHashMap<>();
    /** 参数名到它的采集回调，reload 时按当前文本重放一遍。 */
    private final Map<String, BiConsumer<String, String>> reporters = new LinkedHashMap<>();
    /** 参数名到它的声明，逐框校验用。 */
    private final Map<String, SystemParamSpec> specs = new LinkedHashMap<>();
    /** 参数名到建框时的原始 tooltip，标红时在它后面追加错因，恢复时换回来。 */
    private final Map<String, Component> baseTooltips = new LinkedHashMap<>();
    /** 逐键解析失败留下的错因，一个框一条。 */
    private final Map<String, Component> textProblems = new LinkedHashMap<>();
    /** 跨字段规则失败留下的错因，按规则引用的参数逐个记。 */
    private final Map<String, Component> ruleProblems = new LinkedHashMap<>();
    /** 任一个框变化后的回调，由页签挂上来重跑本族的跨字段规则。 */
    private Runnable onChanged;
    /** 重建期间为真，此时逐个 setValue 触发的回调一律吞掉，建完再报一次。 */
    private boolean building;
    private final int groupWidth;
    private int labelWidth = MIN_LABEL_WIDTH;
    private int rows;

    public ParamGroup(int width) {
        super(0, 0, width, 0, Component.empty(), AbstractScrollArea.defaultSettings(10));
        this.groupWidth = width;
    }

    /**
     * 按声明重建。
     *
     * <p>saved 是该项上次留下的文本，键与 onText 收到的键一致。有则预填，没有就用声明给的默认文本。
     * onText 为 null 表示不采集。
     *
     * <p>顺序固定为先 setValue 再 setResponder：setResponder 只写字段，而 setValue 会走
     * onValueChange 回调 responder，先挂上会把预填也报一遍。
     */
    public void setParams(SystemParams params, Map<String, String> saved, BiConsumer<String, String> onText) {
        this.children.clear();
        this.texts.clear();
        this.reporters.clear();
        this.specs.clear();
        this.baseTooltips.clear();
        this.textProblems.clear();
        this.ruleProblems.clear();
        this.building = true;
        Font font = Minecraft.getInstance().font;
        this.labelWidth = this.labelWidth(font, params);
        this.rows = params.entries().size();
        for (SystemParamSpec spec : params.entries()) {
            StringWidget label = new StringWidget(Component.literal(spec.key()), font);
            EditBox box = new EditBox(font, this.boxWidth(), BOX_HEIGHT, Component.literal(spec.key()));
            box.setMaxLength(MAX_TEXT_LENGTH);
            box.setHint(Component.literal(spec.type().id()));
            Component base = this.tooltip(spec);
            box.setTooltip(Tooltip.create(base));
            String text = saved.getOrDefault(spec.key(), spec.defaultText());
            if (text != null) {
                box.setValue(text);
            }
            box.setResponder(value -> {
                this.mark(spec.key(), SystemSelectionParser.problem(spec, value));
                if (onText != null) {
                    onText.accept(spec.key(), value);
                }
                this.notifyChanged();
            });
            if (onText != null) {
                this.reporters.put(spec.key(), onText);
            }
            this.specs.put(spec.key(), spec);
            this.baseTooltips.put(spec.key(), base);
            this.texts.put(spec.key(), box);
            this.children.add(label);
            this.children.add(box);
        }
        this.setHeight(this.rows * ROW_HEIGHT);
        this.layoutRows();
        this.validateAll();
        this.building = false;
        this.notifyChanged();
    }

    /** 建好框后按当前文本校验一遍：预填值也要标出来，但不回写采集回调。 */
    private void validateAll() {
        for (Map.Entry<String, SystemParamSpec> entry : this.specs.entrySet()) {
            EditBox box = this.texts.get(entry.getKey());
            this.mark(entry.getKey(), SystemSelectionParser.problem(entry.getValue(), box.getValue()));
        }
    }

    /** 挂上变化回调。每次任意一个框的文本变化后调用一次。 */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    private void notifyChanged() {
        if (this.building || this.onChanged == null) {
            return;
        }
        this.onChanged.run();
    }

    /** 当前每个框的文本，键与声明一致。供跨字段规则求值。 */
    public Map<String, String> currentTexts() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, EditBox> entry : this.texts.entrySet()) {
            out.put(entry.getKey(), entry.getValue().getValue());
        }
        return out;
    }

    /**
     * 记下某条跨字段规则失败，把它引用的参数一起标红。
     *
     * <p>只记本组存在的键：规则引用的键都来自本系统的声明，不在本组说明本次选中项变了，那一条作废。
     */
    public void markRule(Set<String> keys, Component message) {
        for (String key : keys) {
            if (this.texts.containsKey(key)) {
                this.ruleProblems.put(key, message);
            }
        }
        this.applyMarks();
    }

    /** 清掉全部跨字段标红，逐键的解析错因不受影响。 */
    public void clearRuleMarks() {
        this.ruleProblems.clear();
        this.applyMarks();
    }

    /** 记下或清掉一个框的逐键错因，然后重刷整组。 */
    private void mark(String key, Component problem) {
        if (problem == null) {
            this.textProblems.remove(key);
        } else {
            this.textProblems.put(key, problem);
        }
        this.applyMarks();
    }

    /**
     * 按两张表重刷每个框的颜色与 tooltip。
     *
     * <p>逐键错因优先于跨字段错因：前者是这一段文本本身不可解析，后者是几个值之间的关系不成立，先修前者
     * 才有意义。
     */
    private void applyMarks() {
        for (Map.Entry<String, EditBox> entry : this.texts.entrySet()) {
            String key = entry.getKey();
            EditBox box = entry.getValue();
            Component problem = this.textProblems.get(key);
            if (problem == null) {
                problem = this.ruleProblems.get(key);
            }
            Component base = this.baseTooltips.get(key);
            if (problem == null) {
                box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
                if (base != null) {
                    box.setTooltip(Tooltip.create(base));
                }
                continue;
            }
            box.setTextColor(INVALID_TEXT_COLOR);
            if (base != null) {
                box.setTooltip(Tooltip.create(Component.empty().append(base).append("\n").append(problem)));
            }
        }
    }

    private int labelWidth(Font font, SystemParams params) {
        int widest = MIN_LABEL_WIDTH;
        for (SystemParamSpec spec : params.entries()) {
            widest = Math.max(widest, font.width(Component.literal(spec.key())) + LABEL_GAP);
        }
        return widest;
    }

    private int boxWidth() {
        return Math.max(MIN_BOX_WIDTH, this.groupWidth - this.labelWidth);
    }

    /** tooltip 第一行是「类型：值」，其余逐条列出该参数的映射；两行的排版都走语言文件。 */
    private Component tooltip(SystemParamSpec spec) {
        MutableComponent text = Component.translatable("createWorld.tab.infs-farlands.param.type",
                spec.type().id());
        for (Map.Entry<String, SystemParamSpec.Keyword> entry : spec.keywords().entrySet()) {
            text.append("\n").append(Component.translatable("createWorld.tab.infs-farlands.param.line",
                    entry.getKey(),
                    Component.translatable(entry.getValue().langKey())));
        }
        return text;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        this.layoutRows();
        for (AbstractWidget child : this.children) {
            child.extractRenderState(graphics, mouseX, mouseY, a);
        }
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        this.layoutRows();
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        this.layoutRows();
    }

    /** 本组从不滚动，收到滚轮就还给外层页面。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    private void layoutRows() {
        int x = this.getX();
        int y = this.getY();
        for (int i = 0; i < this.children.size(); i += 2) {
            this.children.get(i).setPosition(x, y + LABEL_TOP);
            this.children.get(i + 1).setPosition(x + this.labelWidth, y);
            y += ROW_HEIGHT;
        }
    }

    /**
     * 把当前每个框的文本报一遍给采集回调，用于切换系统前把本组填的内容落到暂存处。EditBox 不暴露
     * 已设的 responder，所以这里自己留一份回调，按框的当前文本逐条上报。
     */
    public void reload() {
        for (Map.Entry<String, EditBox> entry : this.texts.entrySet()) {
            BiConsumer<String, String> report = this.reporters.get(entry.getKey());
            if (report != null) {
                report.accept(entry.getKey(), entry.getValue().getValue());
            }
        }
    }

    @Override
    protected int contentHeight() {
        return this.rows * ROW_HEIGHT;
    }

    @Override
    public List<? extends AbstractWidget> children() {
        return Collections.unmodifiableList(this.children);
    }

    @Override
    public Collection<? extends NarratableEntry> getNarratables() {
        return this.children;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
