package com.inf.farlands.client.mixin.debug;

import java.util.Map;

import com.inf.farlands.client.debug.PositionUnitsDebugEntry;
import com.inf.farlands.client.debug.SystemsDebugEntry;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把本 mod 的调试条目登记进原版注册表。
 *
 * <p>DebugScreenEntries.register 是私有的。接口的静态方法不能是抽象方法，类的静态方法也不能抽象，
 * 所以 @Invoker 打静态目标这条路不走；改在类初始化的 RETURN 直接写它自己的表，此时原版条目已全部
 * 登记完，顺序确定，也不需要任何调用点。
 *
 * <p>条目不在两个内置 profile 里，DebugScreenEntryList 对未登记 id 给 NEVER，所以它默认关闭，要在
 * 调试设置界面里开一次；开启状态落在工作目录的 debug-profile.json。
 */
@Mixin(DebugScreenEntries.class)
public class DebugScreenEntriesMixin {

    @Shadow
    @Final
    private static Map<Identifier, DebugScreenEntry> ENTRIES_BY_ID;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void farlands$registerEntries(CallbackInfo ci) {
        ENTRIES_BY_ID.put(SystemsDebugEntry.ID, new SystemsDebugEntry());
        ENTRIES_BY_ID.put(PositionUnitsDebugEntry.ID, new PositionUnitsDebugEntry());
    }
}
