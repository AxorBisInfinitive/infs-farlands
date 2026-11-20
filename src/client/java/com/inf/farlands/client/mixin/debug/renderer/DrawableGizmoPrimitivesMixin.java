package com.inf.farlands.client.mixin.debug.renderer;

import com.inf.farlands.client.render.ChunkBorderLines;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 gizmo 绘制的入口把区块边界线一并画掉。
 *
 * <p>
 * 借这个入口只为一件事：它是渲染 pass 里唯一一处同时拿到恒等 {@code PoseStack} 与宿主
 * {@code MultiBufferSource} 的地方，而这两样正是画相对量顶点所需的。{@code ChunkBorderLines.render} 取走即清，
 * 所以这里被调两次时只有第一次出线。
 *
 * <p>
 * 注入点是公开方法，不涉及 lambda 名与注入点定位。标准组在 {@code LevelRenderer} 的主 pass 里每帧必调，alwaysOnTop
 * 组才有条件，因此本层落在与 vanilla gizmo 相同的 pass 与深度状态里。
 */
@Mixin(DrawableGizmoPrimitives.class)
public class DrawableGizmoPrimitivesMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;)V", at = @At("HEAD"))
    private void farlands$drawChunkBorder(PoseStack poseStack, MultiBufferSource bufferSource, CameraRenderState camera,
            Matrix4fc modelView, CallbackInfo ci) {
        ChunkBorderLines.render(poseStack, bufferSource);
    }
}
