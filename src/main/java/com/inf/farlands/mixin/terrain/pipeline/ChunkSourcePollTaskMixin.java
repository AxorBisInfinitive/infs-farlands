package com.inf.farlands.mixin.terrain.pipeline;

import com.inf.farlands.debug.FreezeWatch;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断：等待期间的读数心跳。
 *
 * <p>{@code MainThreadExecutor.pollTask} 是 {@code managedBlock} 每轮循环都调的那一句，原版自己也在这里
 * 做距离管理器更新与光照调度，所以它在阻塞期间一定会被反复执行。把现场读数挂在这里，就不需要重定向
 * 那个接收者不可命名的 {@code managedBlock}。
 *
 * <p>内层类只能按字符串指定，仓库已有同形先例。定位完成后与同批探针一并删除。
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public class ChunkSourcePollTaskMixin {

    @Inject(method = "pollTask", at = @At("HEAD"))
    private void farlands$pollFreezeWatch(CallbackInfoReturnable<Boolean> cir) {
        FreezeWatch.poll();
    }
}
