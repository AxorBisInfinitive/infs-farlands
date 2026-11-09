package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.simulation.SimulationTimings;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 临时探针，结案即删：把「世界提交之后、呈现之前」那一段切开。
 *
 * <p>
 * {@code GameRenderer.renderLevel} 在 {@code levelRenderer.renderLevel} 之后还要做手部、屏幕效果与延迟特征
 * 提交，这一段整段都在原来的四个探针之外。头尾给出整段，{@code renderAllFeatures} 单独包一层，于是
 * 「手与屏幕效果」等于两者之差。
 *
 * <p>
 * 返回处记为尾段起点，由 {@link com.inf.farlands.client.mixin.render.RenderSystemFlipFrameMixin} 在
 * {@code flipFrame} 进入时结算，覆盖 GUI、{@code blitToScreen} 与 tracy。
 *
 * <p>
 * 特征提交用 {@code @WrapOperation} 而不是 {@code @Redirect}：它对同一调用点是叠加语义，不会吃掉别的注入器。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    /** 本帧 renderLevel 的起点。渲染线程独占。 */
    @Unique
    private long farlands$grenderStart;

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void farlands$grenderEnter(DeltaTracker deltaTracker, CallbackInfo ci) {
        this.farlands$grenderStart = SimulationTimings.begin();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
    private void farlands$grenderLeave(DeltaTracker deltaTracker, CallbackInfo ci) {
        SimulationTimings.end(SimulationTimings.GRENDER, this.farlands$grenderStart);
        SimulationTimings.renderLevelEnded();
    }

    @WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures()V"))
    private void farlands$features(FeatureRenderDispatcher dispatcher, Operation<Void> original) {
        long start = SimulationTimings.begin();
        try {
            original.call(dispatcher);
        } finally {
            SimulationTimings.end(SimulationTimings.FEATURES, start);
        }
    }
}
