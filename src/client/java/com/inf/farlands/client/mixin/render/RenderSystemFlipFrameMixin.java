package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.simulation.SimulationTimings;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.systems.RenderSystem;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 临时探针，结案即删：量「呈现与收尾」这一段。
 *
 * <p>
 * 帧的真实边界在这里。{@code Minecraft} 每帧调一次 {@code flipFrame}，它的体依次是
 * {@code Tesselator.clear}、{@code presentFrame}、tracy、{@code dynamicUniforms.reset}、
 * {@code levelRenderer.endFrame}。其中 {@code presentFrame} 是 GPU 与垂直同步的等待落点，而
 * {@code levelRenderer.endFrame} 才是段 UBO 的 ring 轮转处，所以呈现发生在轮转之前。
 *
 * <p>
 * 返回处同时记为帧边界，供 {@link SimulationTimings#closeOutside()} 在下一次提取开始时结算尾段。
 */
@Mixin(RenderSystem.class)
public class RenderSystemFlipFrameMixin {

    /** 本帧 flipFrame 的起点，0 表示未开表。渲染线程独占。 */
    @Unique
    private static long farlands$flipStart;

    @Inject(method = "flipFrame", at = @At("HEAD"))
    private static void farlands$flipHead(TracyFrameCapture tracyFrameCapture, CallbackInfo ci) {
        SimulationTimings.closeTail();
        farlands$flipStart = SimulationTimings.begin();
    }

    @Inject(method = "flipFrame", at = @At("RETURN"))
    private static void farlands$flipReturn(TracyFrameCapture tracyFrameCapture, CallbackInfo ci) {
        SimulationTimings.end(SimulationTimings.FLIP, farlands$flipStart);
        SimulationTimings.flipEnded();
    }
}
