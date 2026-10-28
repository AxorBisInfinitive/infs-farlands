package com.inf.farlands.client.mixin.simulation;

import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.client.simulation.SimulationTick;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把模拟方块层挂到 {@code Minecraft} 实例上，在 level 装上时驱动一次，并在卸关卡的两条路径上清空它。
 *
 * <p>
 * 实例首次取用时建，不用字段初始化器：Mixin 对 {@code @Unique} 字段的初始化器不保证执行。
 *
 * <p>
 * 驱动挂在 {@code setLevel} 的返回处。它是客户端装上 level 的唯一公共出口，登录与重生两条路都在它上面，
 * 且都在客户端主线程；那时 level 已进引擎。要按 tick 推进的内容不在这里驱动，得另找入口。
 *
 * <p>
 * 清空挂在既有的两个卸关卡出口上，与 {@code ClientWorldState.clearOnLevelDrop} 同一批：三参
 * {@code disconnect} 是所有断线路径的汇合点，{@code clearClientLevel} 是重入配置阶段那条不走 disconnect
 * 的路径。不清的话，上一个 level 的模拟方块会留到下一个 level。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements SimulationHolder {

    @Unique
    private Simulation farlands$simulation;

    @Override
    public Simulation simulation() {
        Simulation simulation = this.farlands$simulation;
        if (simulation == null) {
            simulation = new Simulation();
            this.farlands$simulation = simulation;
        }
        return simulation;
    }

    @Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("RETURN"))
    private void farlands$simulateOnLevelInstall(ClientLevel level, CallbackInfo ci) {
        SimulationTick.tickSimulation((Minecraft) (Object) this);
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("RETURN"))
    private void farlands$clearSimulationOnDisconnect(Screen screen, boolean keepResourcePacks,
            boolean stopSound, CallbackInfo ci) {
        this.simulation().clear();
    }

    @Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("RETURN"))
    private void farlands$clearSimulationOnLevelClear(Screen screen, CallbackInfo ci) {
        this.simulation().clear();
    }
}
