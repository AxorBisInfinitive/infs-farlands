package com.inf.farlands.client.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.inf.farlands.client.simulation.SimulationTimings;

import net.minecraft.client.renderer.MappableRingBuffer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 临时探针，结案即删：把段 UBO 写入里「等围栏」的那段时长单独计出来。
 *
 * <p>
 * {@code DynamicUniformStorage.writeUniforms} 的计时把两种成本合成一个数：一次五兆的映射写入，与循环里第一次
 * {@code currentBuffer()} 触发的 {@code fence.awaitCompletion}。后者是渲染线程在等 GPU，与前者差一个数量级的
 * 修法，所以必须分开。
 *
 * <p>
 * 只在 {@code fences[current]} 非空时计时，也就是这一帧真的会等；其余 19,992 次调用只多两次字段读。整个 ring
 * 也被别的 UBO 使用，所以计数受 {@link SimulationTimings#uniformWriting()} 限定，只统计段 UBO 那一次写入。
 */
@Mixin(MappableRingBuffer.class)
public class MappableRingBufferMixin {

    @Shadow
    @Final
    private GpuFence[] fences;

    @Shadow
    private int current;

    /** 本次调用的起点，0 表示未计时。渲染线程独占，不需要 volatile。 */
    @Unique
    private static long farlands$cbStart;

    @Inject(method = "currentBuffer", at = @At("HEAD"))
    private void farlands$cbHead(CallbackInfoReturnable<GpuBuffer> cir) {
        if (!SimulationTimings.uniformWriting()) {
            return;
        }
        SimulationTimings.cbCall();
        if (this.fences[this.current] != null) {
            farlands$cbStart = System.nanoTime();
        }
    }

    @Inject(method = "currentBuffer", at = @At("RETURN"))
    private void farlands$cbReturn(CallbackInfoReturnable<GpuBuffer> cir) {
        long start = farlands$cbStart;
        if (start == 0L) {
            return;
        }
        farlands$cbStart = 0L;
        SimulationTimings.cbWait(System.nanoTime() - start);
    }
}
