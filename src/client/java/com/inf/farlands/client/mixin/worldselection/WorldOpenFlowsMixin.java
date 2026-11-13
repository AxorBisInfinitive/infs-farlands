package com.inf.farlands.client.mixin.worldselection;

import java.nio.file.Path;

import com.inf.farlands.client.gui.CreatingWorldSimulationConfig;
import com.inf.farlands.client.gui.CreatingWorldSystemsConfig;
import com.mojang.datafixers.util.Pair;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 重建已有世界那条入口的暂存处理。
 *
 * <p>WorldSelectionList 的 recreateWorld 先调本方法读盘，成功后才构造 CreateWorldScreen。所以在本
 * 方法的 RETURN 做两件事：清空上一次的暂存，再按这个世界落盘的 systems.dat 预填。页签随后构造时会
 * 读到这份预填；读取失败由 prefillFrom 自己弹 toast 并保持空映射，界面因此退回默认。
 *
 * <p>方法名在本类里唯一，所以注解只写名字。
 *
 * <p>目标方法有返回值，处理器因此必须收 CallbackInfoReturnable 而不是 CallbackInfo：这一条与是否
 * cancellable 无关，写错会在 APPLY 阶段抛 InvalidInjectionException。类型实参按本仓惯例写成目标
 * 方法的返回类型。
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @Inject(method = "recreateWorldData", at = @At("RETURN"))
    private void farlands$prefillFromWorld(LevelStorageAccess levelSourceAccess,
            CallbackInfoReturnable<Pair<LevelSettings, WorldCreationContext>> cir) {
        CreatingWorldSystemsConfig.reset();
        CreatingWorldSimulationConfig.reset();
        try {
            Path root = levelSourceAccess.getLevelPath(LevelResource.ROOT);
            CreatingWorldSystemsConfig.prefillFrom(root);
            CreatingWorldSimulationConfig.prefillFrom(root);
        } catch (RuntimeException e) {
            // 取世界根失败：留空映射，界面走默认，prefillFrom 的 toast 已覆盖读盘失败
            CreatingWorldSystemsConfig.reset();
            CreatingWorldSimulationConfig.reset();
        }
    }
}
