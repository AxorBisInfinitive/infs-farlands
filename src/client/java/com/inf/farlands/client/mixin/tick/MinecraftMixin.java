package com.inf.farlands.client.mixin.tick;

import com.inf.farlands.client.ClientWorldState;
import com.inf.farlands.client.FarlandsClientTick;

import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 tick 末尾入口，与服务端那条 tick 钩子对称；另外两条卸关卡清理，以及世界创建期的窗口消息泵。
 *
 * Minecraft.tick 每游戏 tick 恰一次，第一句就自增 clientTickCount，所以 RETURN 处拿到的就是
 * 当前 tick 号。入口签名与服务端那条一致取 int，故此处收窄。回收逻辑在
 * {@link FarlandsClientTick}，本类只做转发。
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow
    private long clientTickCount;

    @Inject(method = "tick", at = @At("RETURN"))
    private void farlands$clientTickEnd(CallbackInfo ci) {
        FarlandsClientTick.atEnd((Minecraft) (Object) this, (int) clientTickCount);
    }

    /**
     * 卸关卡后的进程级清理。三参 disconnect 是唯一出口：disconnectFromWorld、disconnectWithSavingScreen、
     * disconnectWithProgressScreen 与两参 disconnect 最终都汇入它。RETURN 处集成服务端线程已死、level 已置空、
     * updateLevelInEngines(null) 已跑、player 已空，旧世界的打包键没有活持有者。
     */
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("RETURN"))
    private void farlands$clearOnDisconnect(Screen screen, boolean keepResourcePacks, boolean stopSound,
            CallbackInfo ci) {
        ClientWorldState.clearOnLevelDrop();
    }

    /**
     * 服务端要求重入配置阶段时的那条卸关卡路径。它不经过 disconnect，也不停集成服务端，所以只清客户端这一侧。
     */
    @Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("RETURN"))
    private void farlands$clearOnLevelClear(Screen screen, CallbackInfo ci) {
        ClientWorldState.clearOnLevelDrop();
    }

    /**
     * 世界创建期的窗口消息泵，补的是 {@code doWorldLoad} 那个等待循环。
     *
     * <p>现象是新建世界时窗口显示未响应，而客户端进程仍在跑帧。机制有两半：这整段加载跑在一次
     * runTick 之内，而窗口消息只在 run 的每帧开头抽一次，于是从按下创建按钮到这段结束，消息一次都不抽，
     * 操作系统数秒后判定无响应。
     *
     * <p>这里在等待循环的每一轮补一次泵。循环之前那次数据包加载不在覆盖内，它由
     * {@code WorldOpenFlows.createFreshLevel} 同步跑完，实测约一秒。
     *
     * <p>不兜异常：与 run 里那次调用同规，GLFW 故障要露出来。
     */
    @Inject(method = "doWorldLoad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;tick()V", shift = At.Shift.AFTER))
    private void farlands$pumpWindowEvents(LevelStorageSource.LevelStorageAccess access, PackRepository packs,
            WorldStem stem, Optional<GameRules> rules, boolean newWorld, CallbackInfo ci) {
        RenderSystem.pollEvents();
    }
}
