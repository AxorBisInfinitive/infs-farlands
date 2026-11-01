package com.inf.farlands.client.mixin.position;

import com.inf.farlands.client.render.FrameMath;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧量级上放弃矿车乘客的插值偏移。
 *
 * <p>
 * 偏移在 {@code EntityRenderer.extractRenderState} 里算成 {@code getCartLerpPosition(partialTicks)} 减一个
 * {@code Mth.lerp}，被减数同样是跨网络上线的 {@code MinecartStep.position}，见
 * {@link MinecartRenderPosMixin}。置空之后乘客画在自己的实体帧上，与车厢那一侧的处理一致。
 * </p>
 *
 * <p>
 * 只判空与非空，不复刻「是不是矿车乘客」那一串条件：{@code passengerOffset} 只有那一处写点，非矿车乘客本来就是
 * 空。这里是全仓唯一保留 {@code 2^53} 判据的地方之一，理由见下：它不是精度阈值。
 * </p>
 */
@Mixin(EntityRenderer.class)
public class PassengerOffsetMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("RETURN"))
    private void farlands$dropPassengerOffset(Entity entity, EntityRenderState state, float partialTicks,
            CallbackInfo ci) {
        if (state.passengerOffset == null) {
            return;
        }
        // 这一处不是精度阈值，是数据源能力：被减数是跨网络上线的 MinecartStep.position，两端都是 ulp 的整数倍。
        // 2^53 以下是原版有定义的区间，插值必须保；之上没有定义，插值徒留 0 或 ±ulp 的假位移。
        double limit = FrameMath.threshold();
        if (Math.abs(entity.getX()) < limit && Math.abs(entity.getY()) < limit
                && Math.abs(entity.getZ()) < limit) {
            return;
        }
        state.passengerOffset = null;
    }
}
