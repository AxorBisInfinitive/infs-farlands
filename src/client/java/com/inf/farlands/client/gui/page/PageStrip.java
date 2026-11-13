package com.inf.farlands.client.gui.page;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.AbstractLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;

/**
 * 页签行：每格按自身内容定宽，多行换行。
 *
 * <p>
 * 不复用 {@code GridLayout.RowHelper}：它的行列在 {@code addChild} 时写死，而这里换行位置要按可用宽度与每格实测
 * 宽现算；并且视口在构造那一刻就把内容里的控件抄进了私有列表，之后重建布局对象不会被采集。所以这里直接持按钮、
 * 在 {@code arrangeElements} 里逐格定位。
 *
 * <p>
 * 换行是贪心：从行首累加，放不下就换行。首格宽于整行时独占一行并按可用宽度封顶，否则贪心不前进。
 */
final class PageStrip extends AbstractLayout {

    private static final int SPACING = 2;

    private final List<AbstractWidget> tabs = new ArrayList<>();

    /** 每格按自己内容量出的宽度，与 tabs 同序。 */
    private final List<Integer> widths = new ArrayList<>();

    private final int tabHeight;

    /** 可用宽度，由布局喂入；决定换行位置与超宽单格的封顶。 */
    private int available;

    PageStrip(int[] tabWidths, int tabHeight) {
        super(0, 0, 0, 0);
        this.tabHeight = tabHeight;
        for (int width : tabWidths) {
            this.widths.add(width);
        }
    }

    void addTab(AbstractWidget tab) {
        this.tabs.add(tab);
    }

    /** 改一格的消息。索引越界即返回：构造期按钮还没加进来。 */
    void setTabMessage(int index, Component message) {
        if (index < 0 || index >= this.tabs.size()) {
            return;
        }
        this.tabs.get(index).setMessage(message);
    }

    /** 布局时喂入可用宽度。宽度变化会改变换行位置，也会改变超宽单格的封顶值。 */
    void setAvailable(int available) {
        this.available = available;
        for (int i = 0; i < this.tabs.size(); i++) {
            this.tabs.get(i).setWidth(this.clampWidth(i));
        }
    }

    /** 单格实际宽度。比整行还宽的格独占一行并按可用宽度封顶，否则换行会不前进。 */
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
