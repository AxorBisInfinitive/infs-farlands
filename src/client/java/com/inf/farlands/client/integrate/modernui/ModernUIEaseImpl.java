package com.inf.farlands.client.integrate.modernui;

import icyllis.modernui.mc.ModernUIMod;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ScrollController;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;

/**
 * 平滑滚动的实现，整层唯一允许出现 ModernUI 类型的类。
 *
 * <p>
 * 引擎用它的 {@link ScrollController}：补间的连续性、时长与插值都由它给，本类只负责把它接到视口上，并
 * 补上整数位置丢掉的小数。时钟用 {@link MuiModApi#getElapsedTime()}，那一份每帧推进，与原版界面无关。
 *
 * <p>
 * 只读一个数、写一个数，不拦截任何方法。外部写入，例如拖动、聚焦滚动与展开上滚，由 {@link #tick()}
 * 发现：视口当前值不等于自己上次写的值就重新起步，并立刻落值，不补间。
 *
 * <p>
 * 步长取 10，与视口构造时用的滚动速率同值。本类只改过程，不改一次滚轮的位移量。
 */
final class ModernUIEaseImpl implements SmoothScrollEngine, ScrollController.IListener {

    /** 一次滚轮事件的位移，与视口的滚动速率同值。 */
    private static final int STEP = 10;

    private final AbstractScrollArea area;

    private final ScrollController controller = new ScrollController(this);

    /** 自己上一次写进视口的值。与当前值不等即说明有外部写入。 */
    private double lastWritten;

    private ModernUIEaseImpl(AbstractScrollArea area) {
        this.area = area;
        this.lastWritten = area.scrollAmount();
    }

    static SmoothScrollEngine create(AbstractScrollArea area) {
        return new ModernUIEaseImpl(area);
    }

    /** 它自己的平滑开关是否开着。启动属性，与它那几个 mixin 的门控读同一份。 */
    static boolean smoothScrollingEnabled() {
        return !Boolean.parseBoolean(
                ModernUIMod.getBootstrapProperty(ModernUIMod.BOOTSTRAP_DISABLE_SMOOTH_SCROLLING));
    }

    @Override
    public void tick() {
        this.controller.setMaxScroll(this.area.maxScrollAmount());
        double current = this.area.scrollAmount();
        if (current != this.lastWritten) {
            // 外部写入：从当前值重新起步并立刻落值，不补间。
            this.controller.scrollTo((float) current);
            this.controller.abortAnimation();
            this.lastWritten = this.area.scrollAmount();
        }
        this.controller.update(MuiModApi.getElapsedTime());
    }

    @Override
    public boolean wheel(double scrollY) {
        this.controller.setMaxScroll(this.area.maxScrollAmount());
        this.controller.scrollBy((float) (-scrollY * STEP));
        return true;
    }

    /**
     * 内容按整数位置摆放，小数在这里补回来，并量化到设备像素：位移只落在整设备像素上，所以画面连续而
     * 不出现亚像素模糊。
     */
    @Override
    public float offset() {
        double amount = this.area.scrollAmount();
        int scale = Minecraft.getInstance().getWindow().getGuiScale();
        return (float) ((int) (((int) amount - amount) * scale)) / (float) scale;
    }

    @Override
    public void onScrollAmountUpdated(ScrollController controller, float amount) {
        this.area.setScrollAmount(amount);
        this.lastWritten = amount;
    }
}
