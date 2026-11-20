package com.inf.farlands.client.mixin.debug.tool.clamp;

import com.inf.farlands.client.debug.tool.clamp.VirtualHit;
import com.inf.farlands.debug.tool.clamp.ClampMode;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 钳制模式的虚拟面射线命中：开启且未按 shift 时，射线向下穿过当前段底平面，返回指向面下那一格的虚拟命中。
 *
 * <p>
 * 算式与射程判据全在 {@link VirtualHit#surface}，与选择框那一侧共用一份。格号算在实体自己的帧里，所以
 * {@code 2^31} 之上不会落到 int 两端，{@code 2^57} 之上也不会按 256 格量化。
 *
 * <p>
 * 只在格号落进可玩界时才返回 {@link BlockHitResult}。界外连一个 {@code BlockPos} 都装不下，返回它只会让交互链拿到
 * 一个饱和坐标的目标，所以那时保留原 MISS：交互本来就不该在那一层发生，选择框由钳制侧的绘制阶段按同一份帧现算。
 */
@Mixin(Entity.class)
public class EntityPickMixin {

    @Inject(method = "pick", at = @At("RETURN"), cancellable = true)
    private void farlands$virtualPick(double hitDistance, float partialTicks, boolean hitFluids,
            CallbackInfoReturnable<HitResult> cir) {
        Entity self = (Entity) (Object) this;
        VirtualHit.clear();
        // 原命中 BLOCK 即保留，不虚拟命中，因为 clip 只命中非空气。
        // 注意 miss 也是 BlockHitResult，type 为 MISS。
        if (cir.getReturnValue() instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            return;
        }
        if (!(self instanceof ClampMode clamp) || !clamp.isClampEnabled() || self.isShiftKeyDown()) {
            return;
        }
        VirtualHit.Surface surface = VirtualHit.surface(self, partialTicks, hitDistance);
        if (surface == null || !WorldBounds.inBlock(surface.x(), surface.y(), surface.z())) {
            // 精确域：不返回饱和命中，选择框由钳制侧的抽取阶段按同一份帧现算。
            return;
        }
        // 可玩界内锚点为零，帧内量就是世界量，落点与格号与旧写法同值。
        BlockPos virtual = new BlockPos(surface.x().toInt(), surface.y().toInt(), surface.z().toInt());
        VirtualHit.set(virtual);
        cir.setReturnValue(new BlockHitResult(surface.location(), Direction.UP, virtual, false));
    }
}
