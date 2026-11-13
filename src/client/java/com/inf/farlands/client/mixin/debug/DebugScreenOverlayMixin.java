package com.inf.farlands.client.mixin.debug;

import java.util.List;

import com.inf.farlands.client.debug.DebugEntryColors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.1.2 版本绑定，升级要重核：{@code extractLines} 是原版私有方法，签名一改这里就找不到。
 *
 * <p>
 * 调试屏的每行是 String，只能走传统 {@code §} 码，拿不到任意 RGB。本注入把带段标记的行拆开，逐段用
 * Component 的样式色画；不带标记的行原样交给 {@code original}，行为与不装本模组逐字相同。
 *
 * <p>
 * 段起点按前缀宽度算，不按段宽累加：整行的背景条用 {@code Font.width(整行)}，即对 advance 之和取一次
 * ceil，逐段累加会多出段数减一的取整误差。前缀含标记，而标记零宽，所以与可见前缀同宽。
 */
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayMixin {

    @WrapOperation(method = "extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
    private static void farlands$drawSegments(GuiGraphicsExtractor graphics, Font font, String line, int x, int y,
            int color, boolean dropShadow, Operation<Void> original) {
        List<DebugEntryColors.Segment> segments = DebugEntryColors.split(line);
        if (segments.size() == 1 && segments.get(0).rgb() == DebugEntryColors.DEFAULT) {
            original.call(graphics, font, line, x, y, color, dropShadow);
            return;
        }
        for (DebugEntryColors.Segment segment : segments) {
            int left = x + font.width(line.substring(0, segment.rawStart()));
            if (segment.rgb() == DebugEntryColors.DEFAULT) {
                graphics.text(font, segment.text(), left, y, color, dropShadow);
            } else {
                graphics.text(font, Component.literal(segment.text()).withStyle(Style.EMPTY.withColor(segment.rgb())),
                        left, y, color, dropShadow);
            }
        }
    }
}
