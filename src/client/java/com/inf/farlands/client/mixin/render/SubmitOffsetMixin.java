package com.inf.farlands.client.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;

import com.inf.farlands.client.render.ExactCameraState;
import com.inf.farlands.client.render.ExactRenderOrigin;
import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 实体位移的实参改在帧里算。
 *
 * <p>
 * 原式是 {@code state.x - camX}，两端都是世界量级 double：{@code 2^60} 处 ulp 是 256，{@code 5e18} 处是 1024，
 * 差值因此落在 ulp 网格上，模型与模拟层相差一整格网格。这里换成帧差，量级由 long 承担、小数由局部量承担。
 *
 * <p>
 * 注入点必须是 {@code submitEntities} 里那一次调用，不能写成整个 {@code submit} 方法：GUI 实体预览
 * 在 {@code GuiEntityRenderer} 里也调 {@code submit}，实参是常量零，状态是当场新建的，改不得。
 *
 * <p>
 * 用 {@code @Redirect} 而不是 {@code @ModifyArgs}：三个实参是原始类型 double，走 Args 的装箱转拆箱没有实机
 * 依据；全签名重定向不引入装箱，代价是这个节点被吃掉。三方若也重定向同一次调用，会被静默跳过。
 */
@Mixin(LevelRenderer.class)
public class SubmitOffsetMixin {

    @Redirect(method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void farlands$submitWithFrames(EntityRenderDispatcher dispatcher, EntityRenderState state,
            CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
            SubmitNodeCollector collector) {
        ExactRenderOrigin origin = (ExactRenderOrigin) state;
        ExactCameraState cameraState = (ExactCameraState) camera;
        if (origin.farlands$originValid() && cameraState.farlands$stateValid()) {
            long originX = origin.farlands$originAnchor(ExactPosition.POS_X);
            long originY = origin.farlands$originAnchor(ExactPosition.POS_Y);
            long originZ = origin.farlands$originAnchor(ExactPosition.POS_Z);
            long cameraX = cameraState.farlands$stateAnchor(ExactPosition.POS_X);
            long cameraY = cameraState.farlands$stateAnchor(ExactPosition.POS_Y);
            long cameraZ = cameraState.farlands$stateAnchor(ExactPosition.POS_Z);
            x = FrameMath.offset(originX, origin.farlands$originLocal(ExactPosition.POS_X), cameraX,
                    cameraState.farlands$stateLocal(ExactPosition.POS_X));
            y = FrameMath.offset(originY, origin.farlands$originLocal(ExactPosition.POS_Y), cameraY,
                    cameraState.farlands$stateLocal(ExactPosition.POS_Y));
            z = FrameMath.offset(originZ, origin.farlands$originLocal(ExactPosition.POS_Z), cameraZ,
                    cameraState.farlands$stateLocal(ExactPosition.POS_Z));
        }
        dispatcher.submit(state, camera, x, y, z, poseStack, collector);
    }
}
