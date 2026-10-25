package com.inf.farlands.client.mixin.gui;

import com.inf.farlands.client.gui.demo.DropdownSelect;
import com.inf.farlands.client.integrate.modernui.ModernUIEase;
import com.inf.farlands.client.integrate.modernui.SmoothScrollable;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 滚动区收到滚轮时的单点路由：展开的下拉列表优先，其次是挂了 ModernUI 平滑引擎的容器，其余交回 vanilla。
 *
 * <p>
 * 26.1.2 的 {@code AbstractScrollArea.mouseScrolled} 不看坐标就滚自己并返回 true。而 {@code Screen} 的
 * 孩子里只有本模组的滚动视口，下拉 {@code DropdownSelect} 只在视口自己的 child 列表里，屏幕那一层取不到
 * 它们。于是指针落在展开的列表上滚轮时，事件被视口自己吃掉，列表不动、页面滚动。
 *
 * <p>
 * 点击没有这个问题：{@code AbstractContainerWidget.mouseClicked} 会先走
 * {@code ContainerEventHandler.super.mouseClicked}，把事件交给 {@code getChildAt} 取到的 child。
 * 滚轮缺的正是这一步转发，这里补上。
 *
 * <p>
 * 两段的判据都在实例上。下拉那段看 child 里有没有 {@code DropdownSelect} 且
 * {@link DropdownSelect#isOverOpenList} 为真：只有指针确实落在某个展开列表内才截走。没有下拉的滚动区扫描
 * 不到候选，行为与 vanilla 逐字相同。平滑那段看该实例有没有挂引擎，没挂即返回假，落到 vanilla 那一支。
 *
 * <p>
 * 两段写在同一处，顺序由代码行序决定。拆成两个 mixin 就要靠 priority 排，而 priority 的先后不是可以依赖
 * 的东西：谁先吃掉滚轮，决定了展开列表在页面上还能不能滚。
 *
 * <p>
 * 混入体零字段。目标方法声明在 {@code AbstractScrollArea} 上，{@code ScrollableLayout$Container} 与
 * {@code AbstractContainerWidget} 都没有它。
 */
@Mixin(AbstractScrollArea.class)
public class DropdownScrollWheelMixin {

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void farlands$routeWheel(double mouseX, double mouseY, double scrollX, double scrollY,
            CallbackInfoReturnable<Boolean> cir) {
        for (GuiEventListener child : ((ContainerEventHandler) this).children()) {
            if (child instanceof DropdownSelect dropdown && dropdown.isOverOpenList(mouseX, mouseY)) {
                dropdown.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
                cir.setReturnValue(true);
                return;
            }
        }
        if (scrollY != 0 && this instanceof SmoothScrollable host
                && ModernUIEase.wheel(host.farlands$engine(), scrollY)) {
            cir.setReturnValue(true);
        }
    }
}
