package com.inf.farlands.client.mixin.worldselection;

import com.inf.farlands.client.gui.SystemsCompat;

import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 缩略图上那个「开始」贴图按系统配置兼容性选择分支。
 *
 * <p>那个贴图不是控件，是 {@code extractContent} 里一条 if 链画出来的，而链上唯一与本模组相关的判据
 * 是 {@code summary.isCompatible()}：为假时画错误图标并挂版本不兼容的 tooltip。这里只把**这一个调用
 * 点**改成「版本兼容且系统配置兼容」，于是不兼容的存档显示错误图标，其余世界逐字不变。
 *
 * <p>不动 {@code LevelSummary.isCompatible} 本身，因为 {@code createInfo} 用它把整行描述换成版本
 * 不兼容文案，专用服务器的启动判据也读它。
 *
 * <p>方法描述符逐字取自 javap。该类里 {@code LevelSummary.isCompatible()} 的调用只有这一处，序号无需
 * 指定。
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public class WorldListEntryMixin {

    @Redirect(method = "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelSummary;isCompatible()Z"))
    private boolean farlands$iconIncompatible(LevelSummary summary) {
        return summary.isCompatible() && !((SystemsCompat) summary).systemsIncompatible();
    }
}
