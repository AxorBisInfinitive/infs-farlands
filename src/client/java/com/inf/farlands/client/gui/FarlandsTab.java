package com.inf.farlands.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.inf.farlands.client.gui.demo.DropdownSelect;
import com.inf.farlands.client.gui.demo.ParamGroup;
import com.inf.farlands.client.gui.viewport.ScrollViewport;
import com.inf.farlands.client.integrate.modernui.ModernUIEase;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemPageHooks;
import com.inf.farlands.terrain.registry.SystemRegistries;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemSelectionParser;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.AbstractLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 创建世界页的附加页签。页签行是原版样式 B 的分页，即 TabButton 排成的一行；每页里逐族放一个
 * 样式 D 的下拉框，选项是注册表里该族系统的 id，下拉下面按该系统的声明生成一组参数文本框。
 *
 * <p>维度不写死：页列表由创建流程喂入，一页对应世界实际拥有的一个维度。系统与维度没有绑定关系，
 * 每页的五族下拉列的都是该族全部系统，任何维度可选任何一个。页标题优先取本模组语言文件里的
 * {@code createWorld.tab.infs-farlands.page.<path>}，没有该键时退回维度 id 本身，所以不认得的
 * 维度显示的是可读的 id，而不是未翻译的键名。
 *
 * <p>原版 TabNavigationBar 在这里用不了：它的 arrangeElements 把内部布局钉在屏幕 y 等于 0 的位置，
 * 而它不是 AbstractWidget，装不进页签的子控件列表。这里用同一套 TabButton 自排一行，贴图、选中时
 * 下移三像素、焦点下划线都与原版一致，只少了页签行左右两段分隔线。原版 TabButton 自己不响应点击，
 * 选中由 TabNavigationBar 设置焦点时驱动，所以这里补上点击即切换。
 *
 * <p>每页的内容各自装在一个 {@link ScrollViewport} 里，各页共占同一块页签区域，随本页一次性挂上屏幕。
 * 切页只改各页叶子的 visible，不动屏幕的控件列表。滚动量因此是每页一份：一页展开下拉引起的位移只影响
 * 该页，切到别的维度页不会带着偏移。
 *
 * <p>下拉的浮层也每页一个，画在该页视口之后：那一层由 DropdownSelect.Layer 承担，因此它的绘制在各页
 * 内容之后，展开的列表盖在参数框之上。
 *
 * <p>下拉宽度按选项文字自动算，四个框共用全部选项里最宽的那个加内边距。每页正文最后留出 POPUP_ROOM
 * 的空白，该页滚动容器的高度才装得下最后一条展开的列表；可视区不够时由 Revealer 滚当前页上来。
 * 选中项变化会改变参数框行数，所以那时要重跑一次布局。
 */
public class FarlandsTab implements Tab {

    private static final String PAGE_KEY_PREFIX = "createWorld.tab.infs-farlands.page.";
    /** 系统标题的键前缀，其后接 `<namespace>.<path>`。 */
    private static final String SYSTEM_KEY_PREFIX = "createWorld.tab.infs-farlands.system.";
    private static final int MARGIN = 16;
    private static final int TAB_HEIGHT = 24;
    private static final int TAB_PADDING = 16;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_PADDING = 8;
    private static final int MIN_FIELD_WIDTH = 120;
    private static final int MAX_FIELD_WIDTH = 300;
    /** 4 行各 12 加边框与余量：正文末尾要留出的浮层高度。 */
    private static final int POPUP_ROOM = 52;
    private static final int REVEAL_SLACK = 4;

    /** 每页各一份滚动容器，它的 AbstractScrollArea 用于把展开的列表滚进可视区。 */
    private final List<DemoPage> pages = new ArrayList<>();
    /** 维度页到族到参数组，提交被拦下时按违规的维度与族定位到该标红的组。 */
    private final Map<Identifier, Map<FamilyKind, ParamGroup>> groups = new HashMap<>();
    /** 维度页到族到族标签。标红与复位都改它的消息，两份文本在建立时算好，按键路径不再分配。 */
    private final Map<Identifier, Map<FamilyKind, FamilyLabel>> labels = new HashMap<>();
    /** 维度页到其中至少有一个系统非法的族。页签的红当且仅当这一集非空。 */
    private final Map<Identifier, Set<FamilyKind>> invalidFamilies = new HashMap<>();
    /** 非法集合变化后的回调，屏幕据此回写创建按钮的可用状态。界面初始化期由屏幕装上。 */
    private Runnable invalidListener;
    /** 页签行。多行换行由它自己按可用宽度排，见 {@link TabStrip}。 */
    private final TabStrip strip;
    private ScreenRectangle lastArea;

    /** 一个族的标签与它的两份文本：正常色与标红色。 */
    private record FamilyLabel(StringWidget widget, Component normal, Component invalid) {
    }

    public FarlandsTab(List<Identifier> dimensions) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;

        // 暂存与界面读同一份维度列表：界面上改了而落盘不含它，或反过来，都会静默丢配置。
        CreatingWorldSystemsConfig.setDimensions(dimensions);

        TabManager manager = new TabManager(widget -> {
        }, widget -> {
        }, page -> {
            if (page != null) {
                ((DemoPage) page).show();
            }
        }, page -> {
            if (page != null) {
                ((DemoPage) page).hide();
            }
        });

        this.pages.clear();
        for (Identifier dimension : dimensions) {
            // 每页各建一份浮层与视口：浮层随本页挂上屏幕，滚动量也只属于本页。
            DropdownSelect.Layer layer = new DropdownSelect.Layer();
            int pageIndex = this.pages.size();
            LinearLayout body = this.familyBody(font, dimension, layer, bottom -> this.reveal(pageIndex, bottom));
            GridLayout content = new GridLayout();
            content.addChild(body, 0, 0);
            this.pages.add(new DemoPage(dimension, pageTitle(dimension), new ScrollViewport(content), layer));
        }

        int[] tabWidths = new int[dimensions.size()];
        for (int i = 0; i < dimensions.size(); i++) {
            tabWidths[i] = this.measureTabWidth(font, dimensions.get(i));
        }
        this.strip = new TabStrip(tabWidths, TAB_HEIGHT);

        for (int i = 0; i < this.pages.size(); i++) {
            DemoPage page = this.pages.get(i);
            page.hide();
            this.strip.addTab(new DemoTabButton(manager, page, tabWidths[i]));
        }
        // 构造期那次 revalidate 因按钮尚不存在而跳过了页签刷新，这里补齐。
        this.refreshAllTabs();

        if (!this.pages.isEmpty()) {
            manager.setCurrentTab(this.pages.get(0), false);
        }
    }

    /**
     * 页标题，两级回退。
     *
     * <p>第一级是本模组私有键 {@code createWorld.tab.infs-farlands.page.<path>}，本模组自己或专门为
     * 本页签适配的人可以覆盖得更细。第二级是维度自身的键 {@code dimension.<namespace>.<path>}，也就是
     * 注册模组给它自己的维度用的那一个，任何模组按惯例写它就能让本页签本地化，不需要知道本模组存在。
     * 两者都没有时退回完整维度 id。
     *
     * <p>第二级不靠 fallback 串委托：{@code TranslatableContents} 的 fallback 是 String，塞进去会被当
     * 字面量原样渲染，别的模组给 {@code dimension.*} 写的翻译就不会被解析。所以这里先问语言表有没有第
     * 一级，没有才换成第二级。语言表按 {@code Language.has} 判存在，判据在渲染前成立。
     */
    private static Component pageTitle(Identifier dimension) {
        String own = PAGE_KEY_PREFIX + dimension.getPath();
        if (Language.getInstance().has(own)) {
            return Component.translatable(own);
        }
        return Component.translatableWithFallback(dimensionKey(dimension), dimension.toString());
    }

    /** 维度自身的语言键，与注册模组的惯例一致：{@code dimension.<namespace>.<path>}。 */
    private static String dimensionKey(Identifier dimension) {
        return "dimension." + dimension.getNamespace() + "." + dimension.getPath();
    }

    /** 单格页签宽度：这一格的标题宽加内边距。每格按自己的内容定宽，不设上限。 */
    private int measureTabWidth(Font font, Identifier dimension) {
        return Math.max(1, font.width(pageTitle(dimension)) + TAB_PADDING);
    }

    /** 一个页：五族系统各一块，块内是标题、下拉、以及按声明生成的参数框。默认项按维度分派。 */
    private LinearLayout familyBody(Font font, Identifier page, DropdownSelect.Layer layer,
            DropdownSelect.Revealer revealer) {
        List<SystemId> biomeIds = SystemRegistries.biomeIds();
        List<SystemId> terrainIds = SystemRegistries.terrainIds();
        List<SystemId> surfaceIds = SystemRegistries.surfaceIds();
        List<SystemId> carverIds = SystemRegistries.carverIds();
        List<SystemId> decorationIds = SystemRegistries.decorationIds();
        int width = this.fieldWidth(font,
                List.of(biomeIds, terrainIds, surfaceIds, carverIds, decorationIds));

        LinearLayout body = LinearLayout.vertical().spacing(8);
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.biome"),
                biomeIds, page, FamilyKind.BIOME, SystemRegistries::biomeClassOf, layer, revealer, width));
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.terrain"),
                terrainIds, page, FamilyKind.TERRAIN, SystemRegistries::terrainClassOf, layer, revealer, width));
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.surface"),
                surfaceIds, page, FamilyKind.SURFACE, SystemRegistries::surfaceClassOf, layer, revealer, width));
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.carver"),
                carverIds, page, FamilyKind.CARVER, SystemRegistries::carverClassOf, layer, revealer, width));
        body.addChild(this.familyBlock(font,
                Component.translatable("createWorld.tab.infs-farlands.family.decoration"),
                decorationIds, page, FamilyKind.DECORATION, SystemRegistries::decorationClassOf, layer, revealer,
                width));
        body.addChild(SpacerElement.height(POPUP_ROOM));
        return body;
    }

    /**
     * 一个族块：标题在上，下面是下拉与它对应的参数组。初始选中先取暂存的选择，没有才用默认项；
     * 下拉变更写回暂存，参数文本由参数组直接写回。
     */
    private Layout familyBlock(Font font, Component title, List<SystemId> ids, Identifier page, FamilyKind kind,
            Function<SystemId, Class<?>> classOf, DropdownSelect.Layer layer,
            DropdownSelect.Revealer revealer, int width) {
        SystemId saved = CreatingWorldSystemsConfig.system(page, kind);
        if (saved == null) {
            saved = SystemsIO.defaultId(page, kind);
        }
        int index = defaultIndex(ids, saved);
        ParamGroup group = new ParamGroup(width);
        this.groups.computeIfAbsent(page, k -> new EnumMap<>(FamilyKind.class)).put(kind, group);
        group.setOnChanged(() -> this.revalidate(page, kind));
        // 标签先建：applySelection 会经 setParams 触发一次 revalidate，那时族标签要在册才标得上红。
        StringWidget label = new StringWidget(title, font);
        this.labels.computeIfAbsent(page, k -> new EnumMap<>(FamilyKind.class))
                .put(kind, new FamilyLabel(label, title, title.copy().withStyle(ChatFormatting.RED)));
        this.applySelection(group, classOf, page, kind, ids.get(index));
        LinearLayout control = LinearLayout.vertical().spacing(4);
        control.addChild(new DropdownSelect(layer, revealer, width, FIELD_HEIGHT, this.idOptions(ids), index,
                i -> {
                    SystemId chosen = ids.get(i);
                    group.reload();
                    CreatingWorldSystemsConfig.select(page, kind, chosen);
                    this.applySelection(group, classOf, page, kind, chosen);
                }));
        control.addChild(group);
        // 与 CommonLayouts.labeledElement 同形：标签在上、控件在下、行距 4，只是标签的引用留在本类。
        LinearLayout block = LinearLayout.vertical().spacing(4);
        block.addChild(label);
        block.addChild(control);
        return block;
    }

    /** 默认项在该族列表里的下标。列表里没有它时退回第一项。 */
    private static int defaultIndex(List<SystemId> ids, SystemId defaultId) {
        return Math.max(0, ids.indexOf(defaultId));
    }

    /** 应用选中项：按声明建参数组，预填该项已存的文本，并把后续编辑写回暂存处。 */
    private void applySelection(ParamGroup group, Function<SystemId, Class<?>> classOf,
            Identifier page, FamilyKind kind, SystemId id) {
        group.setParams(SystemParams.declaredBy(classOf.apply(id)),
                CreatingWorldSystemsConfig.text(page, kind, id),
                (key, value) -> CreatingWorldSystemsConfig.putText(page, kind, id, key, value));
        this.relayout();
    }

    /**
     * 重跑一族的跨字段规则。逐键检查由参数组自己做，这里只管几个参数之间的关系。
     *
     * <p>先清掉上一轮的规则标红再重算，否则修好一条之后其余的会一直红着。逐键错因不受影响，它由参数组
     * 自己维护。
     */
    private void revalidate(Identifier page, FamilyKind kind) {
        ParamGroup group = this.group(page, kind);
        if (group == null) {
            return;
        }
        group.clearRuleMarks();
        SystemId id = CreatingWorldSystemsConfig.system(page, kind);
        if (id == null) {
            id = SystemsIO.defaultId(page, kind);
        }
        List<SystemParams.Violation> found = SystemSelectionParser.violations(
                CreatingWorldSystemsConfig.classOf(kind, id), group.currentTexts());
        for (SystemParams.Violation violation : found) {
            group.markRule(violation.keys(), violation.message());
        }
        this.markFamily(page, kind, !found.isEmpty());
    }

    /**
     * 标红或复位一个族：族标签的颜色，与它所在维度页的页签。
     *
     * <p>判据是违规列表是否非空，与落盘那一次检验同源，所以页签红与「拦住创建」逐条一致。
     * 只在颜色上区分，不改文本、不改布局。
     */
    private void markFamily(Identifier page, FamilyKind kind, boolean invalid) {
        Set<FamilyKind> families = this.invalidFamilies.computeIfAbsent(page,
                k -> EnumSet.noneOf(FamilyKind.class));
        if (invalid) {
            families.add(kind);
        } else {
            families.remove(kind);
        }
        Map<FamilyKind, FamilyLabel> pageLabels = this.labels.get(page);
        FamilyLabel label = pageLabels == null ? null : pageLabels.get(kind);
        if (label != null) {
            label.widget().setMessage(invalid ? label.invalid() : label.normal());
        }
        this.refreshTab(page);
        if (this.invalidListener != null) {
            this.invalidListener.run();
        }
    }

    /** 是否有任一维度页的任一系统参数非法。屏幕据此把创建按钮置灰并吃掉回车。 */
    public boolean hasInvalid() {
        for (Set<FamilyKind> families : this.invalidFamilies.values()) {
            if (!families.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 装上非法集合变化的回调。屏幕在界面初始化期调一次，重建界面时随新页签重装。 */
    public void setInvalidListener(Runnable listener) {
        this.invalidListener = listener;
    }

    /** 该维度页的页签颜色：该页至少一个族非法即红，否则正常色。 */
    private void refreshTab(Identifier page) {
        // 构造期按钮还没建，而那时 revalidate 已经在跑。留给构造函数末尾的 refreshAllTabs 补。
        if (this.strip == null) {
            return;
        }
        Set<FamilyKind> families = this.invalidFamilies.get(page);
        boolean invalid = families != null && !families.isEmpty();
        for (int i = 0; i < this.pages.size(); i++) {
            DemoPage demoPage = this.pages.get(i);
            if (demoPage.dimension.equals(page)) {
                this.strip.setTabMessage(i, invalid ? demoPage.invalidTitle : demoPage.title);
                return;
            }
        }
    }

    private void refreshAllTabs() {
        for (DemoPage demoPage : this.pages) {
            this.refreshTab(demoPage.dimension);
        }
    }

    private ParamGroup group(Identifier page, FamilyKind kind) {
        Map<FamilyKind, ParamGroup> families = this.groups.get(page);
        return families == null ? null : families.get(kind);
    }

    /** 参数框行数变了，内容高度跟着变，所以要重跑一次布局，否则会被裁在旧高度里。 */
    private void relayout() {
        ScreenRectangle area = this.lastArea;
        if (area != null) {
            this.doLayout(area);
        }
    }

    /**
     * 四个下拉共用一个宽度：取全部选项里最宽的那个加内边距，再夹在上下限之间。文字宽度由字体量，
     * 不写死。
     *
     * <p>量的必须是 {@link #systemTitle} 的结果而不是注册 id：标题比 id 短，按 id 量会让下拉框留着 id 的
     * 宽度。
     */
    private int fieldWidth(Font font, List<List<SystemId>> families) {
        int widest = 0;
        for (List<SystemId> ids : families) {
            for (SystemId id : ids) {
                widest = Math.max(widest, font.width(systemTitle(id)));
            }
        }
        return Mth.clamp(widest + FIELD_PADDING, MIN_FIELD_WIDTH, MAX_FIELD_WIDTH);
    }

    /** 下拉选项的文字，与选中项显示的、量宽度用的都是同一份标题。 */
    private List<Component> idOptions(List<SystemId> ids) {
        List<Component> options = new ArrayList<>(ids.size());
        for (SystemId id : ids) {
            options.add(systemTitle(id));
        }
        return options;
    }

    /**
     * 一个系统在界面上的标题。
     *
     * <p>键的形态是 {@code createWorld.tab.infs-farlands.system.<namespace>.<path>}。本模组登记的 35 个
     * 系统都有这条键；外部模组注册的系统不会有，此时退回原始 id，界面至少还能看出是哪个系统。
     */
    private static Component systemTitle(SystemId id) {
        String key = SYSTEM_KEY_PREFIX + id.value().getNamespace() + "." + id.value().getPath();
        if (Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        return Component.literal(id.toString());
    }

    /**
     * 展开的列表越过该页可视区下边界时，把该页内容向上滚，让列表整体落在可视区里。只动该页那一份
     * 滚动量，其余维度页不受影响。
     */
    private void reveal(int page, int listBottom) {
        if (page < 0 || page >= this.pages.size()) {
            return;
        }
        ScrollViewport area = this.pages.get(page).viewport();
        if (area == null) {
            return;
        }
        int overflow = listBottom + REVEAL_SLACK - area.getBottom();
        if (overflow > 0) {
            area.setScrollAmount(area.scrollAmount() + overflow);
        }
    }

    @Override
    public Component getTabTitle() {
        return Component.translatable("createWorld.tab.infs-farlands.title");
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
        for (DemoPage page : this.pages) {
            page.visitChildren(childrenConsumer);
        }
    }

    /**
     * 整块内容靠页签区域的左上摆放，上下左右各留 MARGIN 像素。滚动区的最小宽度撑到可用宽度，否则
     * 容器宽度贴着内容，滚动条会画在内容右边而不是页面右边。
     *
     * <p>页签行在这里按可用宽度重排：宽度信息只有布局时才有，所以构造期先用上限宽排一次，首个布局
     * 之后才是最终宽度。首次布局因此有一次跳变，属预期。
     */
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
        for (DemoPage page : this.pages) {
            ScrollViewport viewport = page.viewport();
            viewport.relayout(available, bodyHeight);
            viewport.setPosition(screenRectangle.left() + MARGIN, bodyTop);
        }
    }

    /**
     * 页签行：每格按自身内容定宽，多行换行。
     *
     * <p>不复用 {@code GridLayout.RowHelper}：它的行列在 {@code addChild} 时写死，而这里换行位置要按
     * 可用宽度与每格实测宽现算；并且视口在构造那一刻就把内容里的控件抄进了私有列表，之后重建布局对象
     * 不会被采集。所以这里直接持按钮、在 {@code arrangeElements} 里逐格定位。
     *
     * <p>换行是贪心：从行首累加，放不下就换行。首格宽于整行时独占一行并按可用宽度封顶，否则贪心不前进。
     */
    private static final class TabStrip extends AbstractLayout {

        private static final int SPACING = 2;

        private final List<AbstractWidget> tabs = new ArrayList<>();
        /** 每格按自己内容量出的宽度，与 tabs 同序。 */
        private final List<Integer> widths = new ArrayList<>();
        private final int tabHeight;
        /** 可用宽度，由布局喂入；决定换行位置与超宽单格的封顶。 */
        private int available;

        private TabStrip(int[] tabWidths, int tabHeight) {
            super(0, 0, 0, 0);
            this.tabHeight = tabHeight;
            for (int width : tabWidths) {
                this.widths.add(width);
            }
        }

        private void addTab(AbstractWidget tab) {
            this.tabs.add(tab);
        }

        /** 改一格的消息。索引越界即返回：构造期按钮还没加进来。 */
        private void setTabMessage(int index, Component message) {
            if (index < 0 || index >= this.tabs.size()) {
                return;
            }
            this.tabs.get(index).setMessage(message);
        }

        /** 布局时喂入可用宽度。宽度变化会改变换行位置，也会改变超宽单格的封顶值。 */
        private void setAvailable(int available) {
            this.available = available;
            for (int i = 0; i < this.tabs.size(); i++) {
                this.tabs.get(i).setWidth(this.clampWidth(i));
            }
        }

        /**
         * 单格实际宽度。比整行还宽的格独占一行并按可用宽度封顶，否则换行会不前进。
         */
        private int clampWidth(int index) {
            if (this.available <= 0) {
                return this.widths.get(index);
            }
            return Math.min(this.widths.get(index), this.available);
        }

        @Override
        public void arrangeElements() {
            int x = this.getX();
            int y = this.getY();
            int rowWidth = 0;
            int row = 0;
            for (int i = 0; i < this.tabs.size(); i++) {
                int width = this.clampWidth(i);
                if (rowWidth > 0 && rowWidth + SPACING + width > this.available) {
                    row++;
                    rowWidth = 0;
                }
                this.tabs.get(i).setWidth(width);
                this.tabs.get(i).setPosition(x + rowWidth, y + row * (this.tabHeight + SPACING));
                rowWidth += width + SPACING;
            }
        }

        @Override
        public void visitChildren(Consumer<LayoutElement> visitor) {
            this.tabs.forEach(visitor);
        }

        @Override
        public int getWidth() {
            int widest = 0;
            int rowWidth = 0;
            for (int i = 0; i < this.tabs.size(); i++) {
                int width = this.clampWidth(i);
                if (rowWidth > 0 && rowWidth + SPACING + width > this.available) {
                    widest = Math.max(widest, rowWidth - SPACING);
                    rowWidth = 0;
                }
                rowWidth += width + SPACING;
            }
            return Math.max(widest, Math.max(0, rowWidth - SPACING));
        }

        @Override
        public int getHeight() {
            if (this.tabs.isEmpty()) {
                return 0;
            }
            int rows = 1;
            int rowWidth = 0;
            for (int i = 0; i < this.tabs.size(); i++) {
                int width = this.clampWidth(i);
                if (rowWidth > 0 && rowWidth + SPACING + width > this.available) {
                    rows++;
                    rowWidth = 0;
                }
                rowWidth += width + SPACING;
            }
            return rows * this.tabHeight + (rows - 1) * SPACING;
        }
    }

    /**
     * 页签行里的一格。原版 TabButton 自己不响应点击，选中由 TabNavigationBar 设置焦点时驱动；
     * 这里独立成行，所以补上点击即切换。
     */
    private static final class DemoTabButton extends TabButton {

        private final TabManager manager;

        private DemoTabButton(TabManager manager, Tab tab, int width) {
            super(manager, tab, width, TAB_HEIGHT);
            this.manager = manager;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            // 点已选中的那格不是进页，与原版一样不发声也不发事件。真换页才算一次进页。
            if (this.manager.getCurrentTab() == this.tab()) {
                return;
            }
            this.manager.setCurrentTab(this.tab(), true);
            SystemPageHooks.fireEntered(((DemoPage) this.tab()).dimension);
        }
    }

    /**
     * 一页：维度、标题、本页的滚动视口、本页的浮层。内容随本页一次性挂上屏幕，切页只改这一页全部
     * 叶子的 visible。滚动量属于本页的 {@link ScrollViewport}，与其余页无关。
     *
     * <p>维度只用于进页钩子的载荷：点这一格换页时把维度 id 交给登记过的动作。
     */
    private static final class DemoPage implements Tab {

        private final Identifier dimension;
        private final Component title;
        /** 页签标红用的同一份标题。颜色在建立时算好，按键路径不再分配。 */
        private final Component invalidTitle;
        private final ScrollViewport viewport;
        private final DropdownSelect.Layer layer;
        private final List<AbstractWidget> leaves = new ArrayList<>();

        private DemoPage(Identifier dimension, Component title, ScrollViewport viewport,
                DropdownSelect.Layer layer) {
            this.dimension = dimension;
            this.title = title;
            this.invalidTitle = title.copy().withStyle(ChatFormatting.RED);
            this.viewport = viewport;
            this.layer = layer;
            this.leaves.add(viewport);
            this.leaves.add(layer);
            // 装了 ModernUI 且它自己的平滑开关开着时，给本页的视口挂上它的补间引擎；否则空转。
            ModernUIEase.attach(viewport);
        }

        @Override
        public Component getTabTitle() {
            return this.title;
        }

        @Override
        public Component getTabExtraNarration() {
            return Component.empty();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> childrenConsumer) {
            // 视口与浮层依次挂上屏幕，浮层在后，展开的列表才画在参数框之上。
            childrenConsumer.accept(this.viewport);
            childrenConsumer.accept(this.layer);
        }

        @Override
        public void doLayout(ScreenRectangle screenRectangle) {
        }

        private ScrollViewport viewport() {
            return this.viewport;
        }

        private void show() {
            this.setVisible(true);
        }

        private void hide() {
            this.setVisible(false);
        }

        private void setVisible(boolean visible) {
            for (AbstractWidget leaf : this.leaves) {
                leaf.visible = visible;
            }
        }
    }
}
