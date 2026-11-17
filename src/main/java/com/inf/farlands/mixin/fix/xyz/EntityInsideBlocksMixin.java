package com.inf.farlands.mixin.fix.xyz;

import com.inf.farlands.util.world.WorldBounds;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 端点出界时不跑实体穿行检测。
 *
 * <p>
 * `Entity.checkInsideBlocks` 经 `BlockGetter.forEachBlockIntersectedBetween` 走 `addCollisionsAlongTravel` 的 DDA。
 * 那段循环的起点由 `Mth.frac` 定，`frac` 修好之前它在 `|坐标| ≥ 2^63` 处走不完；修好之后循环只有十几步，但格号是
 * `Mth.floor` 饱和过的 int 端点，再加步长会回绕到世界另一端，于是每一 tick 都会 `getBlockState` 几个位于
 * `±2^31` 的坐标。
 *
 * <p>
 * vanilla 的方块只在 int 内存在，端点出界时那一片本来就是空气，跳过与「没有方块内效果」一致。判据与射线检测的守卫
 * 共用 {@link WorldBounds#inBlockAbsolute(Vec3)}。
 */
@Mixin(Entity.class)
public class EntityInsideBlocksMixin {

    @Inject(method = "checkInsideBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/InsideBlockEffectApplier$StepBasedCollector;Lit/unimi/dsi/fastutil/longs/LongSet;I)I", at = @At("HEAD"), cancellable = true)
    private void farlands$skipOutsideBounds(Vec3 from, Vec3 to,
            InsideBlockEffectApplier.StepBasedCollector collector, LongSet visitedBlocks, int maxIterations,
            CallbackInfoReturnable<Integer> cir) {
        if (!WorldBounds.inBlockAbsolute(from) || !WorldBounds.inBlockAbsolute(to)) {
            cir.setReturnValue(0);
        }
    }
}
