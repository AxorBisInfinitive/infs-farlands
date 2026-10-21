package com.inf.farlands.client.mixin.worldselection;

import java.util.Arrays;
import java.util.List;

import com.inf.farlands.client.gui.CreatingWorldSystemsConfig;
import com.inf.farlands.client.gui.FarlandsTab;
import com.inf.farlands.terrain.registry.SystemPageHooks;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给创建世界页追加一个页签，并按页签里的非法状态控制页脚的创建按钮。
 *
 * <p>init 里的页签数组是调用点现场构造后传给 TabNavigationBar$Builder.addTabs 的，把那个数组换成
 * 长度加一的即可，不必读 tabNavigationBar 字段，也不必覆写 init。新页追加在末尾，所以
 * selectTab(0, false) 选中的仍是游戏页，原有三页的序号不变。
 *
 * <p>维度取自 WorldCreationContext 的 selectedDimensions，即**真正进世界的**那一份，不是注册表里的
 * 全部维度。若取后者，预设里已经去掉的维度也会列成页，那些页的选择会写进 systems.dat 而维度并不存在。
 *
 * <p>页签构造需要维度列表，而 @ModifyArg 的 handler 拿不到目标实例，所以在 init 头部先把列表存进
 * 本 mixin 的 @Unique 字段，@ModifyArg 再读它。该字段挂在被注入的 CreateWorldScreen 实例上，与该屏
 * 同生命周期，不需要清。
 *
 * <p>创建按钮是就地建、只喂给 {@code LinearLayout.addChild} 的，类上没有字段，所以在那个调用点截获。
 * 同一处落初次可用状态并装回调，参数一改就回写。回车那条路走的是 vanilla {@code keyPressed} 末尾对
 * {@code isConfirmation()} 直接调 {@code onCreate}，按钮的 active 管不到它，因此在方法入口按确认键拦
 * 一次：判定由此完全离开 {@code onCreate}。
 *
 * <p>target 串按 javap 打 clientonly jar 的描述符逐字抄写：本仓库无 refmap，注解里的 target 编译期
 * 不校验，写错只会在打开本页时抛 MixinTransformerError。
 */
@Mixin(CreateWorldScreen.class)
public class CreateWorldScreenMixin {

    @Unique
    private List<Identifier> farlandsDimensions = List.of();

    /** 本屏的页签实例。创建按钮的可用状态与回车拦截都读它。 */
    @Unique
    private FarlandsTab farlandsTab;

    /** 本屏幕实例的首屏是否已发过进页。界面重建会再走一次页签构造，靠它与首屏那一发区分。 */
    @Unique
    private boolean farlandsEnteredFirstPage;

    /** 目标类的私有字段，读它取该世界的维度集合。 */
    @Shadow
    private WorldCreationUiState uiState;

    @Inject(method = "init", at = @At("HEAD"))
    private void farlands$captureDimensions(CallbackInfo ci) {
        this.farlandsDimensions = this.uiState.getSettings()
                .selectedDimensions().dimensions().keySet().stream()
                .map(key -> key.identifier())
                .toList();
    }

    @ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/tabs/TabNavigationBar$Builder;addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)Lnet/minecraft/client/gui/components/tabs/TabNavigationBar$Builder;"), index = 0)
    private Tab[] farlands$appendTab(Tab[] tabs) {
        Tab[] extended = Arrays.copyOf(tabs, tabs.length + 1);
        FarlandsTab tab = new FarlandsTab(this.farlandsDimensions);
        this.farlandsTab = tab;
        extended[tabs.length] = tab;
        // 首屏那一页也算一次进页，每个屏幕实例一次：重建世界走的是新屏幕实例，因此照发；窗口缩放
        // 只是同一实例的界面重建，标记挡住重复。
        if (!this.farlandsEnteredFirstPage && !this.farlandsDimensions.isEmpty()) {
            this.farlandsEnteredFirstPage = true;
            SystemPageHooks.fireEntered(this.farlandsDimensions.get(0));
        }
        return extended;
    }

    /**
     * 截获页脚的创建按钮，落初次可用状态并装回调。
     *
     * <p>序号取自 javap：{@code init} 里 {@code LinearLayout.addChild} 只出现两次，第一次是创建钮，
     * 第二次是取消钮。此处 {@code farlandsTab} 已由上面的 addTabs 注入建好，非法集合也已算过。
     */
    @ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;", ordinal = 0), index = 0)
    private LayoutElement farlands$bindCreateButton(LayoutElement child) {
        if (child instanceof Button button && this.farlandsTab != null) {
            FarlandsTab tab = this.farlandsTab;
            button.active = !tab.hasInvalid();
            tab.setInvalidListener(() -> button.active = !tab.hasInvalid());
        }
        return child;
    }

    /**
     * 新建与测试世界的重置点。挂这个入口而不是 init 里：init 会被 Screen.rebuildWidgets 重新调用，
     * 改窗口大小一类就会再进一次，在那里清空会把用户填的东西丢掉。
     *
     * <p>方法名在本类里唯一，所以注解只写名字。
     */
    @Inject(method = "openCreateWorldScreen", at = @At("HEAD"))
    private static void farlands$resetConfig(CallbackInfo ci) {
        CreatingWorldSystemsConfig.reset();
    }

    /**
     * 非法时吃掉回车。
     *
     * <p>vanilla 的 {@code keyPressed} 在末尾对 {@code isConfirmation()} 直接调 {@code onCreate}，绕开
     * 按钮，而灰按钮只挡点击。注入点在方法入口，早于 tabNavigationBar 与 super 两处转发，但只对确认键
     * 生效，Ctrl 加数字与 Ctrl+Tab 不受影响。
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void farlands$blockConfirmWhileInvalid(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.isConfirmation() && this.farlandsTab != null && this.farlandsTab.hasInvalid()) {
            cir.setReturnValue(true);
        }
    }
}
