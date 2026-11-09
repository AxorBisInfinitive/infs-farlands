package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.render.NanStaticOverlay;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 位置含 {@code NaN} 时往世界画面盖一层噪点。
 *
 * <p>
 * 钩子取 {@code ScreenEffectRenderer.renderScreenEffect} 的末尾。它在 {@code GameRenderer.renderWorld} 里
 * 排在手持物之后、HUD 之前，且是无条件调用的：{@code hideGui} 只作为实参传进去，只决定物品激活动画那一支。
 * 所以画在这里既压在世界之上，又不受 F1 影响，也不会盖住 HUD。
 *
 * <p>
 * 批提交不用管：调用点之后紧接 {@code bufferSource.endBatch()}，我们写进同一个 {@code MultiBufferSource}
 * 的顶点会被它一起刷出去。
 *
 * <p>
 * 处理体不读 {@code hideGui}，这是有意的；读它会退化成随 HUD 一起隐藏。
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectOverlayMixin {

    @Shadow
    @Final
    private MultiBufferSource bufferSource;

    @Inject(method = "renderScreenEffect", at = @At("TAIL"))
    private void farlands$nanStaticOverlay(boolean isFirstPerson, boolean isSleeping, float partialTicks,
            SubmitNodeCollector submitNodeCollector, boolean hideGui, CallbackInfo ci) {
        NanStaticOverlay.render(this.bufferSource);
    }
}
