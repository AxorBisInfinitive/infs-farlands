package com.inf.farlands.client.mixin.position;

import com.inf.farlands.client.render.FrameMath;

import net.minecraft.client.renderer.entity.AbstractMinecartRenderer;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧量级上放弃新矿车的插值位置，让车厢落回它自己的实体帧。
 *
 * <p>
 * 原式在 {@code AbstractMinecartRenderer.getRenderOffset} 里加一项 {@code renderPos - state}。被减数是
 * {@code NewMinecartBehavior.getCartLerpPosition} 的返回值，它插值的是 {@code MinecartStep.position}，而那三个
 * 分量以 {@code Vec3.STREAM_CODEC} 跨网络上线，到客户端时已被舍到当前量级的 ulp 网格上。所以那一项在
 * {@code 2^60} 只能取 0 或正负 256，帧化改不到被减数。
 * </p>
 *
 * <p>
 * 置空之后 {@code getRenderOffset} 的判据 {@code state.renderPos != null} 不成立，偏移退化成
 * {@code super.getRenderOffset}，车厢画在自己实体帧的插值位置上，与其它实体同一套。代价是丢掉亚 tick 插值，
 * 车厢按 tick 跳，最差一个 tick 的位移；也丢掉与乘客那一项对齐的保证，两者各自用各自的帧，靠近网格中点时可能
 * 落进相邻格点。两处代价都只在帧量级上出现。
 * </p>
 *
 * <p>
 * 阈值取 {@code 2^53}，三轴都低于它时不动，正常坐标逐位不变。
 * </p>
 */
@Mixin(AbstractMinecartRenderer.class)
public class MinecartRenderPosMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;Lnet/minecraft/client/renderer/entity/state/MinecartRenderState;F)V", at = @At("RETURN"))
    private void farlands$dropRenderPos(AbstractMinecart entity, MinecartRenderState state, float partialTicks,
            CallbackInfo ci) {
        if (state.renderPos == null) {
            return;
        }
        if (!FrameMath.wide(entity.getX(), entity.getY(), entity.getZ())) {
            return;
        }
        state.renderPos = null;
    }
}
