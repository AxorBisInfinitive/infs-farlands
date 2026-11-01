package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.render.ExactCameraOrigin;
import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 到相机距离改在帧里算。
 *
 * <p>
 * 原式是两个世界量级 double 的差再平方：{@code 2^60} 处差值只能是 0 或 256，平方后只剩 0 与 65536 两档，
 * 名称牌的 {@code 4096} 判据与影子的 {@code 256} 强度因此变成二值跳变。帧差连续，两个判据都回到原义。
 *
 * <p>
 * 唯一调用点是 {@code EntityRenderer.extractRenderState} 写入 {@code distanceToCameraSq} 那一句，所以改这一处
 * 即覆盖名称牌、计分行与影子强度三处消费者。
 */
@Mixin(EntityRenderDispatcher.class)
public class DistanceToCameraMixin {

    @Shadow
    public Camera camera;

    @ModifyReturnValue(method = "distanceToSqr(Lnet/minecraft/world/entity/Entity;)D", at = @At("RETURN"))
    private double farlands$frameDistance(double original, Entity entity) {
        if (!(entity instanceof ExactPosition holder)) {
            return original;
        }
        if (!(this.camera instanceof ExactCameraOrigin cameraOrigin) || !cameraOrigin.farlands$cameraValid()) {
            return original;
        }
        long originX = holder.farlands$anchor(ExactPosition.POS_X);
        long originY = holder.farlands$anchor(ExactPosition.POS_Y);
        long originZ = holder.farlands$anchor(ExactPosition.POS_Z);
        long cameraX = cameraOrigin.farlands$cameraAnchor(ExactPosition.POS_X);
        long cameraY = cameraOrigin.farlands$cameraAnchor(ExactPosition.POS_Y);
        long cameraZ = cameraOrigin.farlands$cameraAnchor(ExactPosition.POS_Z);
        double dx = FrameMath.offset(originX, holder.farlands$local(ExactPosition.POS_X), cameraX,
                cameraOrigin.farlands$cameraLocal(ExactPosition.POS_X));
        double dy = FrameMath.offset(originY, holder.farlands$local(ExactPosition.POS_Y), cameraY,
                cameraOrigin.farlands$cameraLocal(ExactPosition.POS_Y));
        double dz = FrameMath.offset(originZ, holder.farlands$local(ExactPosition.POS_Z), cameraZ,
                cameraOrigin.farlands$cameraLocal(ExactPosition.POS_Z));
        return dx * dx + dy * dy + dz * dz;
    }
}
