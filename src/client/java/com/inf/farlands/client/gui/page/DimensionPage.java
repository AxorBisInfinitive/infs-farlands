package com.inf.farlands.client.gui.page;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.inf.farlands.client.gui.demo.DropdownSelect;
import com.inf.farlands.client.gui.viewport.ScrollViewport;
import com.inf.farlands.client.integrate.modernui.ModernUIEase;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 一个维度页：维度、标题、本页的滚动视口、本页的浮层。
 *
 * <p>
 * 内容随本页一次性挂上屏幕，切页只改这一页全部叶子的 {@code visible}，不动屏幕的控件列表。滚动量属于本页的
 * {@link ScrollViewport}，与其余页无关。浮层挂在视口之后，展开的下拉列表因此画在参数框之上。
 *
 * <p>
 * {@code doLayout} 是空的：页的摆放由页签统一做，因为它要同时排页签行与所有页。{@code TabManager} 会调到这里，
 * 所以必须实现。
 */
public final class DimensionPage implements Tab {

    private final Identifier dimension;
    private final Component title;
    private final Component invalidTitle;
    private final ScrollViewport viewport;
    private final DropdownSelect.Layer layer;
    private final List<AbstractWidget> leaves = new ArrayList<>();

    public DimensionPage(Identifier dimension, Component title, ScrollViewport viewport,
            DropdownSelect.Layer layer) {
        this.dimension = dimension;
        this.title = title;
        this.invalidTitle = title.copy().withStyle(ChatFormatting.RED);
        this.viewport = viewport;
        this.layer = layer;
        this.leaves.add(viewport);
        this.leaves.add(layer);
        // 装了 ModernUI，且它自己的平滑开关开着时，给本页的视口挂上它的补间引擎；否则空转。
        // 挂载点是 ScrollViewport 实现的 SmoothScrollable，门控与失败降级都在 ModernUIEase 里。
        ModernUIEase.attach(viewport);
    }

    public Identifier dimension() {
        return this.dimension;
    }

    public ScrollViewport viewport() {
        return this.viewport;
    }

    /** 页签标红用的同一份标题，颜色在建立时算好。 */
    public Component invalidTitle() {
        return this.invalidTitle;
    }

    public void show() {
        this.setVisible(true);
    }

    public void hide() {
        this.setVisible(false);
    }

    private void setVisible(boolean visible) {
        for (AbstractWidget leaf : this.leaves) {
            leaf.visible = visible;
        }
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
        childrenConsumer.accept(this.viewport);
        childrenConsumer.accept(this.layer);
    }

    @Override
    public void doLayout(ScreenRectangle screenRectangle) {
    }
}
