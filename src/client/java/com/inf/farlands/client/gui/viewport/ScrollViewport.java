package com.inf.farlands.client.gui.viewport;

import java.util.ArrayList;
import java.util.List;

import com.inf.farlands.client.integrate.modernui.ModernUIEase;
import com.inf.farlands.client.integrate.modernui.SmoothScrollEngine;
import com.inf.farlands.client.integrate.modernui.SmoothScrollable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.CommonComponents;

/**
 * 竖向滚动视口。内容布局的坐标只承载滚动量的整数部分，小数在绘制期补偿。
 *
 * <p>
 * 滚动只有一个写入口 {@link #setScrollAmount(double)}，语义是绝对的：钳制、写字段、把内容按绝对位置
 * 摆一次。任何按增量挪内容的写法都不要加回来，那会让布局坐标与滚动量之间出现第二个基准。
 *
 * <p>
 * 绘制顺序有硬约束：先开裁剪再平移，因为 {@code enableScissor} 会把矩形按当前姿态变换后入栈，先平移会让
 * 裁剪跟着内容走。子控件矩形因此已经是滚动后的屏幕位置，命中与 hover 都不需要坐标换算，误差只来自那不足
 * 一个设备像素的小数。
 *
 * <p>
 * 滚动条、钳制、拖动换算沿用 {@link AbstractScrollArea}，不重写。
 */
public final class ScrollViewport extends AbstractContainerWidget implements SmoothScrollable {

    private final Layout content;

    private final List<AbstractWidget> children = new ArrayList<>();

    private SmoothScrollEngine engine;

    public ScrollViewport(Layout content) {
        super(0, 0, 0, 0, CommonComponents.EMPTY, AbstractScrollArea.defaultSettings(10));
        this.content = content;
        content.visitWidgets(this.children::add);
    }

    /** 内容可用宽：容器宽减去滚动条宽度。使用方按它排版，就不必自己预留。 */
    public int contentWidth() {
        return Math.max(0, this.getWidth() - this.scrollbarWidth());
    }

    /** 重排内容并重算高度。内容高度变化或容器尺寸变化后调它。 */
    public void relayout(int width, int maxHeight) {
        this.setWidth(width);
        this.content.arrangeElements();
        this.setHeight(Math.min(this.content.getHeight(), Math.max(0, maxHeight)));
        this.refreshScrollAmount();
    }

    @Override
    protected int contentHeight() {
        return this.content.getHeight();
    }

    /** 唯一写入点。钳制交给父类，这里只负责让内容跟上新的滚动量。 */
    @Override
    public void setScrollAmount(double amount) {
        super.setScrollAmount(amount);
        this.content.setY(this.getY() - (int) this.scrollAmount());
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        this.content.setX(x);
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        this.content.setY(y - (int) this.scrollAmount());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTicks) {
        SmoothScrollEngine ease = this.engine;
        if (ease != null) {
            ModernUIEase.tick(ease);
        }
        float offset = ease == null ? 0.0F : ModernUIEase.offset(ease);
        graphics.enableScissor(this.getX(), this.getY(), this.getX() + this.getWidth(),
                this.getY() + this.getHeight());
        boolean shifted = offset != 0.0F;
        if (shifted) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(0.0F, offset);
        }
        try {
            for (AbstractWidget child : this.children) {
                child.extractRenderState(graphics, mouseX, mouseY, partialTicks);
            }
        } finally {
            if (shifted) {
                graphics.pose().popMatrix();
            }
            graphics.disableScissor();
        }
        this.extractScrollbar(graphics, mouseX, mouseY);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return this.children;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }

    /**
     * 焦点交给子控件。同值直接返回：父类没有同值判断，会把已经聚焦的容器先置 false 再置 true，而 false
     * 会清空它内部整棵子树的焦点、true 是空操作，输入框因此再也打不进字。
     *
     * <p>
     * 键盘切换焦点时把目标滚进可视区。判据是可视区矩形与目标矩形之差，写的是同一个滚动量，不改布局。
     */
    @Override
    public void setFocused(GuiEventListener focused) {
        if (focused != null && focused == this.getFocused()) {
            return;
        }
        super.setFocused(focused);
        if (focused == null || !Minecraft.getInstance().getLastInputType().isKeyboard()) {
            return;
        }
        ScreenRectangle area = this.getRectangle();
        ScreenRectangle focusedRect = focused.getRectangle();
        int topDelta = focusedRect.top() - area.top();
        int bottomDelta = focusedRect.bottom() - area.bottom();
        double rate = this.scrollRate();
        if (topDelta < 0) {
            this.setScrollAmount(this.scrollAmount() + topDelta - rate);
        } else if (bottomDelta > 0) {
            this.setScrollAmount(this.scrollAmount() + bottomDelta + rate);
        }
    }

    /** 方向键导航的边界：没有子焦点时按可视区与内容高给出垂直边界。 */
    @Override
    public ScreenRectangle getBorderForArrowNavigation(ScreenDirection opposite) {
        GuiEventListener focused = this.getFocused();
        return focused != null ? focused.getBorderForArrowNavigation(opposite)
                : new ScreenRectangle(this.getX(), this.getY(), this.getWidth(), this.contentHeight())
                        .getBorder(opposite);
    }

    @Override
    public SmoothScrollEngine farlands$engine() {
        return this.engine;
    }

    @Override
    public void farlands$engine(SmoothScrollEngine engine) {
        this.engine = engine;
    }
}
