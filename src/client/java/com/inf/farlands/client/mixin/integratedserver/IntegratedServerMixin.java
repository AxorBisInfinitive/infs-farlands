package com.inf.farlands.client.mixin.integratedserver;

import com.inf.farlands.client.gui.CreatingWorldSimulationConfig;
import com.inf.farlands.client.gui.CreatingWorldSystemsConfig;
import com.inf.farlands.simulation.serialize.SimulationIO;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.client.server.IntegratedServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把创建世界页签的选择交给服务端，让它成为这个世界落盘的 systems.dat。
 *
 * <p>
 * 注入点是 {@code initServer} 的 HEAD。它是服务端线程的第一格，{@code createLevels} 与主世界
 * 的 {@code ServerLevel} 构造都在它下游，而 {@link SystemsIO#resolve} 正是在那次构造里唯一一次
 * 读配置；在这里放进去，{@code resolve} 必然看得到。落盘仍由 {@code SavedDataStorage} 在
 * {@code initServer} 尾部的 {@code saveEverything} 完成，本 mixin 不碰文件。
 *
 * <p>
 * 目标类只有集成服务器，所以「仅单人」由类本身承担：专用服务器的 {@code MinecraftServer}
 * 子类不加载本 mixin。
 *
 * <p>
 * 暂存由客户端线程写、服务端线程读。{@code MinecraftServer.spin} 先构造服务器再
 * {@code thread.start()}，{@code start()} 的 happens-before 保证读侧看得到写侧的值，不需要额外同步。
 *
 * <p>
 * 只在世界尚未初始化时暂存：{@link SystemsIO#resolve} 也只在那种世界里取用暂存值。加载已有世界时
 * 界面的残留文本不该参与解析，否则一次输错后取消的创建流程会把加载别的世界一起炸掉。
 *
 * <p>
 * 新建或重建时文本非法仍由解析抛异常直穿，世界创建中止并留下可见的失败，与既有的
 * {@code SystemsIO.read} 严格语义一致；界面侧另有提交前的拦截，正常情况下走不到这里。
 *
 * <p>
 * 处理器收 {@link CallbackInfoReturnable} 而不是
 * {@code CallbackInfo}：{@code initServer}
 * 返回 boolean，判据是目标方法非 void，与是否 cancellable 无关。写错会在 APPLY 阶段抛
 * {@code InvalidInjectionException}，而构建全程通过。
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {

    @SuppressWarnings("resource")
    @Inject(method = "initServer", at = @At("HEAD"))
    private void farlands$stageSystems(CallbackInfoReturnable<Boolean> cir) {
        IntegratedServer self = (IntegratedServer) (Object) this;
        if (!self.getWorldData().overworldData().isInitialized()) {
            SystemsIO.stageForLevelCreation(CreatingWorldSystemsConfig.buildSystemsData());
            // 模拟层的选择同样在 level 构造之前交给档案层：两份档案都必须在第一个 level 之前就位。
            SimulationIO.stageForLevelCreation(CreatingWorldSimulationConfig.buildSimulationData());
        }
    }
}
