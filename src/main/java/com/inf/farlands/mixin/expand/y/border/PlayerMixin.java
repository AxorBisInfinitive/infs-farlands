package com.inf.farlands.mixin.expand.y.border;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家位置的 Y 钳制，与 {@code expand.xz.border.PlayerMixin} 那道 XZ 钳制同形。
 *
 * <p>
 * vanilla 的 {@code Player.tick} 只钳 X 与 Z，末句是 {@code setPos(nx, this.getY(), nz)}，Y 原样传回；
 * 而 Y 方向在本 port 没有别的位置钳制，`WorldBounds` 的界是读写门、{@code EntitySectionWindow} 的界是刻门，
 * 两者都不写回位置。所以三轴不对称，本条把 Y 补到与 XZ 同一个区间。
 *
 * <p>
 * 两个常数照抄 XZ 那道，区间是 {@code [~borderAbsoluteMax + 1, borderAbsoluteMax - 1]}。注入点取
 * {@code tick()V} 的 RETURN，`javap` 里该方法体只有一条 return，落在 XZ 钳制与 `updatePlayerPose` 之后。
 *
 * <p>
 * 与 XZ 同档的边界：子类在自己的 {@code tick} 里若在 {@code super.tick()} 之后再移动玩家，那一次移动不在
 * 本条范围内，下一 tick 才钳上。
 */
@Mixin(Player.class)
public class PlayerMixin {

    /** 越界时拉回并写回位置，正常坐标不动。 */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void farlands$clampY(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        double y = Mth.clamp(self.getY(), ~FarlandsConfig.borderAbsoluteMax + 1,
                FarlandsConfig.borderAbsoluteMax - 1);
        if (y != self.getY()) {
            self.setPos(self.getX(), y, self.getZ());
        }
    }
}
