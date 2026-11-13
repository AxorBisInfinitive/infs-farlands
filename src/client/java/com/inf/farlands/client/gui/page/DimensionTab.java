package com.inf.farlands.client.gui.page;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.inf.farlands.client.gui.demo.DropdownSelect;
import com.inf.farlands.client.gui.viewport.ScrollViewport;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 维度页签的共同骨架：按维度建页，每页按族建行，页签行自己排，校验结果聚合成「有没有非法」。
 *
 * <p>
 * 子类只回答四件事：页标题、本页签有哪些族、族选择存在哪里、进页时做什么。其余全部在这里，所以两个页签的
 * 布局、换行、可见性、标红与校验链只有一份实现。
 *
 * <p>
 * 校验链只有一条：{@code ParamGroup} 先逐键标红，{@link FamilyRow#revalidate()} 再跑跨字段，这里把结果聚合
 * 进 {@code invalidFamilies}，随后页签标红并通知 {@code invalidListener}。子类可以不接最后一步，测试页签
 * 就没有创建按钮可接。
 *
 * <p>
 * 页标题两级回退：本模组键 {@code createWorld.tab.infs-farlands.page.<path>}，没有就用维度自身的
 * {@code dimension.<namespace>.<path>}，两者都没有时退回完整维度 id。
 */
public abstract class DimensionTab implements Tab {

    private static final int MARGIN = 16;

    private static final int TAB_HEIGHT = 24;

    private static final int TAB_PADDING = 16;

    private static final int MIN_FIELD_WIDTH = 120;

    private static final int MAX_FIELD_WIDTH = 300;

    private static final int FIELD_PADDING = 8;

    /** 正文末尾留出的浮层高度：四行各 12 加边框与余量。 */
    private static final int POPUP_ROOM = 52;

    private static final int REVEAL_SLACK = 4;

    private static final String PAGE_KEY_PREFIX = "createWorld.tab.infs-farlands.page.";

    /** 系统标题的键前缀，其后接 {@code <namespace>.<path>}。 */
    private static final String SYSTEM_KEY_PREFIX = "createWorld.tab.infs-farlands.system.";

    private final List<DimensionPage> pages = new ArrayList<>();

    /** 维度页到族到族行，标红与重算都按它定位。 */
    private final Map<Identifier, Map<FamilyKind, FamilyRow>> rows = new HashMap<>();

    /** 维度页到其中至少有一个系统非法的族。页签的红当且仅当这一集非空。 */
    private final Map<Identifier, Set<FamilyKind>> invalidFamilies = new HashMap<>();

    private final PageStrip strip;

    private ScreenRectangle lastArea;

    /** 非法集合变化后的回调。创建世界页签据此回写创建按钮的可用状态。 */
    private Runnable invalidListener;

    private final List<Identifier> dimensions;

    protected DimensionTab(List<Identifier> dimensions) {
        this.dimensions = List.copyOf(dimensions);
        this.onInit(this.dimensions);
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        TabManager manager = new TabManager(widget -> {
        }, widget -> {
        }, page -> {
            if (page != null) {
                ((DimensionPage) page).show();
            }
        }, page -> {
            if (page != null) {
                ((DimensionPage) page).hide();
            }
        });
        int width = this.fieldWidth(font);
        List<FamilyKind> families = this.families();
        for (Identifier dimension : this.dimensions) {
            // 每页各建一份浮层与视口：浮层随本页挂上屏幕，滚动量也只属于本页。
            DropdownSelect.Layer layer = new DropdownSelect.Layer();
            int pageIndex = this.pages.size();
            LinearLayout body = LinearLayout.vertical().spacing(8);
            Map<FamilyKind, FamilyRow> pageRows = new EnumMap<>(FamilyKind.class);
            this.rows.put(dimension, pageRows);
            for (FamilyKind kind : families) {
                FamilyRow row = new FamilyRow(dimension, kind, ids(kind), classOf(kind), this.store(),
                        this::defaultId, layer,
                        bottom -> this.reveal(pageIndex, bottom), width, font, familyTitle(kind),
                        () -> this.onRowChanged(dimension, kind), this::relayout);
                pageRows.put(kind, row);
                body.addChild(row.layout());
            }
            body.addChild(SpacerElement.height(POPUP_ROOM));
            GridLayout content = new GridLayout();
            content.addChild(body, 0, 0);
            this.pages.add(new DimensionPage(dimension, pageTitle(dimension), new ScrollViewport(content), layer));
        }

        int[] tabWidths = new int[this.dimensions.size()];
        for (int i = 0; i < this.dimensions.size(); i++) {
            tabWidths[i] = Math.max(1, font.width(pageTitle(this.dimensions.get(i))) + TAB_PADDING);
        }
        this.strip = new PageStrip(tabWidths, TAB_HEIGHT);
        for (int i = 0; i < this.pages.size(); i++) {
            DimensionPage page = this.pages.get(i);
            page.hide();
            this.strip.addTab(new PageTabButton(manager, page, tabWidths[i], TAB_HEIGHT, this::onPageEntered));
        }
        // 预填放最后：它会触发一次校验，而校验要能从 rows 里查回本行。
        for (Map.Entry<Identifier, Map<FamilyKind, FamilyRow>> entry : this.rows.entrySet()) {
            for (FamilyRow row : entry.getValue().values()) {
                row.applySelection();
            }
        }
        this.refreshAllTabs();
        if (!this.pages.isEmpty()) {
            manager.setCurrentTab(this.pages.get(0), false);
        }
    }

    // ---- 子类回答的四件事 ----

    /** 外层页签行的标题。 */
    protected abstract Component tabTitle();

    /** 本页签每页建哪些族，顺序即界面从上到下的顺序。 */
    protected abstract List<FamilyKind> families();

    /** 族选择存在哪里。 */
    protected abstract FamilySelectionStore store();

    /** 进入某个维度页时要做的事。默认什么都不做。 */
    protected void onPageEntered(Identifier dimension) {
    }

    /**
     * 维度表已就绪、页还没建之前调一次。创建世界页签在这里把维度登记进暂存处，落盘读的是同一份。
     *
     * <p>构造期回调，实现方此时不应访问自己的字段。
     */
    protected void onInit(List<Identifier> dimensions) {
    }

    // ---- Tab ----

    @Override
    public Component getTabTitle() {
        return this.tabTitle();
    }

    @Override
    public Component getTabExtraNarration() {
        return Component.empty();
    }

    @Override
    public void visitChildren(Consumer<AbstractWidget> childrenConsumer) {
        // 页签行与各页内容一起挂上屏幕。TabManager 只为当前页调用本方法，
        // 其余页的控件不在屏幕的 child 列表里，与切页的可见性切换配套。
        this.strip.visitWidgets(childrenConsumer);
        for (DimensionPage page : this.pages) {
            page.visitChildren(childrenConsumer);
        }
    }

    @Override
    public void doLayout(ScreenRectangle screenRectangle) {
        this.lastArea = screenRectangle;
        int available = Math.max(0, screenRectangle.width() - MARGIN * 2);
        this.strip.setAvailable(available);
        // 页签行不在滚动区里，位置要自己给：贴页签区左上，先量出它占多高。
        this.strip.setPosition(screenRectangle.left() + MARGIN, screenRectangle.top() + MARGIN);
        this.strip.arrangeElements();
        int bodyTop = screenRectangle.top() + MARGIN + this.strip.getHeight() + MARGIN;
        int bodyHeight = Math.max(0, screenRectangle.height() - MARGIN * 2 - this.strip.getHeight() - MARGIN);
        // 各页摆在同一位置，只由本页的可见性决定谁被画出来。每页各扣一份滚动条预留。
        for (DimensionPage page : this.pages) {
            ScrollViewport viewport = page.viewport();
            viewport.relayout(available, bodyHeight);
            viewport.setPosition(screenRectangle.left() + MARGIN, bodyTop);
        }
    }

    // ---- 校验与标红 ----

    /** 是否有任一维度页的任一系统参数非法。 */
    public boolean hasInvalid() {
        for (Set<FamilyKind> families : this.invalidFamilies.values()) {
            if (!families.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 装上非法集合变化的回调。 */
    public void setInvalidListener(Runnable listener) {
        this.invalidListener = listener;
    }

    private void onRowChanged(Identifier page, FamilyKind kind) {
        Map<FamilyKind, FamilyRow> pageRows = this.rows.get(page);
        FamilyRow row = pageRows == null ? null : pageRows.get(kind);
        if (row == null) {
            return;
        }
        this.markFamily(page, kind, row.revalidate());
    }

    /**
     * 标红或复位一个族：族标签的颜色，与它所在维度页的页签。
     *
     * <p>判据是违规列表是否非空，与落盘那一次检验同源，所以页签红与「拦住创建」逐条一致。
     */
    private void markFamily(Identifier page, FamilyKind kind, boolean invalid) {
        Set<FamilyKind> families = this.invalidFamilies.computeIfAbsent(page,
                k -> EnumSet.noneOf(FamilyKind.class));
        if (invalid) {
            families.add(kind);
        } else {
            families.remove(kind);
        }
        Map<FamilyKind, FamilyRow> pageRows = this.rows.get(page);
        FamilyRow row = pageRows == null ? null : pageRows.get(kind);
        if (row != null) {
            row.setLabelInvalid(invalid);
        }
        this.refreshTab(page);
        if (this.invalidListener != null) {
            this.invalidListener.run();
        }
    }

    /** 该维度页的页签颜色：该页至少一个族非法即红，否则正常色。 */
    private void refreshTab(Identifier page) {
        Set<FamilyKind> families = this.invalidFamilies.get(page);
        boolean invalid = families != null && !families.isEmpty();
        for (int i = 0; i < this.pages.size(); i++) {
            DimensionPage demoPage = this.pages.get(i);
            if (demoPage.dimension().equals(page)) {
                this.strip.setTabMessage(i, invalid ? demoPage.invalidTitle() : demoPage.getTabTitle());
                return;
            }
        }
    }

    private void refreshAllTabs() {
        for (DimensionPage page : this.pages) {
            this.refreshTab(page.dimension());
        }
    }

    // ---- 布局辅助 ----

    /** 参数框行数变了，内容高度跟着变，所以要重跑一次布局，否则会被裁在旧高度里。 */
    private void relayout() {
        ScreenRectangle area = this.lastArea;
        if (area != null) {
            this.doLayout(area);
        }
    }

    /**
     * 展开的列表越过该页可视区下边界时，把该页内容向上滚，让列表整体落在可视区里。只动该页那一份滚动量。
     */
    private void reveal(int page, int listBottom) {
        if (page < 0 || page >= this.pages.size()) {
            return;
        }
        ScrollViewport area = this.pages.get(page).viewport();
        int overflow = listBottom + REVEAL_SLACK - area.getBottom();
        if (overflow > 0) {
            area.setScrollAmount(area.scrollAmount() + overflow);
        }
    }

    /**
     * 本页签全部族的选项里最宽的那个加内边距，再夹在上下限之间。量的必须是标题而不是注册 id：
     * 标题比 id 短，按 id 量会让下拉框留着 id 的宽度。
     */
    private int fieldWidth(Font font) {
        int widest = 0;
        for (FamilyKind kind : this.families()) {
            for (SystemId id : ids(kind)) {
                widest = Math.max(widest, font.width(systemTitle(id)));
            }
        }
        return Mth.clamp(widest + FIELD_PADDING, MIN_FIELD_WIDTH, MAX_FIELD_WIDTH);
    }

    // ---- 与注册表同源的四张表 ----

    /** 该族在本层注册表里的全部系统，顺序即登记顺序。构造期就会被调到，实现方此时不能访问自己的字段。 */
    protected abstract List<SystemId> ids(FamilyKind kind);

    /** 该族 id 到实现类的取法，供声明解析与校验。约束同 {@link #ids}。 */
    protected abstract Function<SystemId, Class<?>> classOf(FamilyKind kind);

    /** 族标题的键，与语言文件里那五条同名。 */
    static Component familyTitle(FamilyKind kind) {
        return Component.translatable("createWorld.tab.infs-farlands.family."
                + kind.name().toLowerCase(Locale.ROOT));
    }

    /**
     * 一个系统在界面上的标题。键的形态是 {@code createWorld.tab.infs-farlands.system.<namespace>.<path>}；
     * 外部模组注册的系统没有这条键，此时退回原始 id，界面至少还能看出是哪个系统。
     */
    static Component systemTitle(SystemId id) {
        String key = SYSTEM_KEY_PREFIX + id.value().getNamespace() + "." + id.value().getPath();
        if (Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        return Component.literal(id.toString());
    }

    /**
     * 页标题，两级回退。
     *
     * <p>第一级是本模组私有键，第二级是维度自身的 {@code dimension.<namespace>.<path>}，两者都没有时退回完整
     * 维度 id。第二级不靠 fallback 串委托：{@code TranslatableContents} 的 fallback 是 String，塞进去会被当字面量
     * 原样渲染，别的模组给 {@code dimension.*} 写的翻译就不会被解析。
     */
    static Component pageTitle(Identifier dimension) {
        String own = PAGE_KEY_PREFIX + dimension.getPath();
        if (Language.getInstance().has(own)) {
            return Component.translatable(own);
        }
        return Component.translatableWithFallback(
                "dimension." + dimension.getNamespace() + "." + dimension.getPath(), dimension.toString());
    }

    /** 该页签登记的维度，保序。 */
    protected List<Identifier> dimensions() {
        return this.dimensions;
    }

    /** 取该维度页的族行，供子类需要时定位。 */
    protected FamilyRow row(Identifier page, FamilyKind kind) {
        Map<FamilyKind, FamilyRow> pageRows = this.rows.get(page);
        return pageRows == null ? null : pageRows.get(kind);
    }

    /** 当前被选中的默认项判据，与落盘同源。供子类在需要时复用。 */
    /**
     * 该维度该族没选过时的默认项。由子类给：两层各有一套注册表与默认表，默认值也必须同源自家的那一套。
     */
    protected abstract SystemId defaultId(Identifier page, FamilyKind kind);
}
