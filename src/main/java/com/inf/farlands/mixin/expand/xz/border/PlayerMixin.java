package com.inf.farlands.mixin.expand.xz.border;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.world.entity.player.Player;

@Mixin(Player.class)
public class PlayerMixin {
    /**
     * outside 为真时两端换成无穷，Mth.clamp 对一切值恒等，含 ±Inf 与 NaN，位置因此不再被夹。
     */
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = 2.9999999E7))
    private static double maxPos(double value) {
        return FarlandsConfig.outside ? Double.POSITIVE_INFINITY
                : FarlandsConfig.borderAbsoluteMax - 1;
    }

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -2.9999999E7))
    private static double minPos(double value) {
        return FarlandsConfig.outside ? Double.NEGATIVE_INFINITY
                : ~FarlandsConfig.borderAbsoluteMax + 1;
    }
}
