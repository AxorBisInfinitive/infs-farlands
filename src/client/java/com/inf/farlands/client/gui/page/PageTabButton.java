package com.inf.farlands.client.gui.page;

import java.util.function.Consumer;

import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;

/**
 * 页签行里的一格。原版 {@code TabButton} 自己不响应点击，选中由 {@code TabNavigationBar} 设置焦点时驱动；
 * 这里独立成行，所以补上点击即切换。
 *
 * <p>
 * 进页动作由构造时传入的回调决定，不再硬编码：创建世界页签要在进页时触发 {@code SystemPageHooks}，别的页签
 * 传空回调即可。
 */
final class PageTabButton extends TabButton {

    private final TabManager manager;

    private final Consumer<Identifier> onEnter;

    PageTabButton(TabManager manager, DimensionPage page, int width, int height, Consumer<Identifier> onEnter) {
        super(manager, page, width, height);
        this.manager = manager;
        this.onEnter = onEnter;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        // 点已选中的那格不是进页，与原版一样不发声也不发事件。真换页才算一次进页。
        if (this.manager.getCurrentTab() == this.tab()) {
            return;
        }
        this.manager.setCurrentTab(this.tab(), true);
        this.onEnter.accept(((DimensionPage) this.tab()).dimension());
    }
}
