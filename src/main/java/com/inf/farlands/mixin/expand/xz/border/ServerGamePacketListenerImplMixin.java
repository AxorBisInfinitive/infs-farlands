package com.inf.farlands.mixin.expand.xz.border;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * 移动包坐标的边界放宽到世界边界。
 *
 * <p>
 * 服务端收移动包时把客户端报来的坐标钳进固定区间，横向取自 {@code clampHorizontal}，竖直取自
 * {@code clampVertical}，两处的常量都与维度高度和世界边界无关。竖直一处不放开，玩家与乘坐载具在
 * 极端 Y 的坐标会被压回钳制值上，客户端继续上行也会被服务端按距离判据拉回。
 *
 * <p>
 * 被替换的常量取 {@link FarlandsConfig#borderAbsoluteMax} 这一对，即 int 值域全量 -2147483648 至
 * 2147483647，共 2^32 个整数，中点为 -0.5，两侧等距。与
 * {@code expand.xz.border.LevelMixin} 的 {@code isInWorldBoundsHorizontal} 同边界。
 *
 * <p>
 * 竖直的两条走 {@code @ModifyConstant}，即只换边界值，保留 {@code clampVertical} 里
 * {@code Mth.clamp} 的形状；该方法体内两个常量互异，各命中一次，无 ordinal 歧义。
 *
 * <p>
 * outside 为真时两端再换成无穷，Mth.clamp 对一切值恒等，含 ±Inf 与 NaN。横向那一处本来就恒等，不受开关影响。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

    @Inject(method = "clampHorizontal", at = @At("HEAD"), cancellable = true)
    private static void clampHorizontal(double value, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(value);
    }

    @ModifyConstant(method = "clampVertical", constant = @Constant(doubleValue = 2.0E7))
    private static double farlands$maxVerticalClamp(double original) {
        return FarlandsConfig.outside ? Double.POSITIVE_INFINITY : FarlandsConfig.borderAbsoluteMax;
    }

    @ModifyConstant(method = "clampVertical", constant = @Constant(doubleValue = -2.0E7))
    private static double farlands$minVerticalClamp(double original) {
        return FarlandsConfig.outside ? Double.NEGATIVE_INFINITY : ~FarlandsConfig.borderAbsoluteMax;
    }
}
