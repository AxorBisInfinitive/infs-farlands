package com.inf.farlands.client.mixin.worldselection;

import com.inf.farlands.client.gui.SelectionCompat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界条目的两处：缩略图按兼容性选分支，描述行的悬浮展示不兼容的细节。
 *
 * <p>
 * 缩略图上那个「开始」贴图不是控件，是 {@code extractContent} 里一条 if 链画出来的，而链上唯一与本模组相关的判据
 * 是 {@code summary.isCompatible()}：为假时画错误图标并挂版本不兼容的 tooltip。这里只把这一个调用点改成「版本兼容
 * 且三层都兼容」，于是不兼容的存档显示错误图标，其余世界逐字不变。
 *
 * <p>
 * 不动 {@code LevelSummary.isCompatible} 本身，因为 {@code createInfo} 用它把整行描述换成版本不兼容文案，专用
 * 服务器的启动判据也读它。
 *
 * <p>
 * 悬浮那一段在构造末尾挂：行内只留一个短标记，三层细节各一行。不能用 {@code @ModifyArg} 打 vanilla 那处
 * {@code Tooltip.create}，因为它只在描述超宽时才执行，行内缩短之后常常不执行。
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public class WorldListEntryMixin {

    @Shadow
    @Final
    private LevelSummary summary;

    @Shadow
    @Final
    private StringWidget infoText;

    @Redirect(method = "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelSummary;isCompatible()Z"))
    private boolean farlands$iconIncompatible(LevelSummary summary) {
        SelectionCompat compat = (SelectionCompat) summary;
        return summary.isCompatible() && !compat.systemsIncompatible() && !compat.simulationIncompatible()
                && !compat.mappingIncompatible();
    }

    /** 构造末尾补一层悬浮：描述行加短标记，下面每层一行红字。只在该世界真的不兼容时覆盖 vanilla 的 tooltip。 */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void farlands$detailTooltip(WorldSelectionList outer, WorldSelectionList list, LevelSummary levelSummary,
            CallbackInfo ci) {
        SelectionCompat compat = (SelectionCompat) this.summary;
        Component text = this.summary.getInfo();
        boolean any = false;
        if (compat.systemsIncompatible()) {
            text = farlands$detail(text, SelectionCompat.SYSTEMS_KEY);
            any = true;
        }
        if (compat.simulationIncompatible()) {
            text = farlands$detail(text, SelectionCompat.SIMULATION_KEY);
            any = true;
        }
        if (compat.mappingIncompatible()) {
            text = farlands$detail(text, SelectionCompat.MAPPING_KEY);
            any = true;
        }
        if (any) {
            this.infoText.setTooltip(Tooltip.create(text));
        }
    }

    /** 换行加一段红字。硬换行只能是字面的换行符，断行器对它有专门分支。 */
    @Unique
    private static Component farlands$detail(Component base, String key) {
        return base.copy().append(Component.literal("\n"))
                .append(Component.translatable(key).withStyle(ChatFormatting.RED));
    }
}
