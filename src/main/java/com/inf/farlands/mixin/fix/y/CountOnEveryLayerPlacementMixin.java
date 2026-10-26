package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 逐层放置的向下找地面改成有界扫描。
 *
 * <p>
 * {@code findOnGroundYPosition} 从高度图起点逐格向下找地面，走到 {@code getMinY() + 1} 为止，运行期是
 * -63。空列上极端 Y 会一路走到底；同一次放置里这个方法按 count 调用，量会放大。
 *
 * <p>
 * 地板取 {@code clamp(起点 - maxCapIter, 可玩下界, 可玩上界)}，起点是本方法自己的形参 {@code yStart}。
 *
 * <p>
 * 两侧都取可玩界。原版下界 -64 不能当夹取的一侧：负侧起点在它以下时它反过来成了地板，循环第一轮即假。
 * 上界夹住是因为循环条件带 {@code + 1}，起点逼近 int 上界时会回绕。
 */
@Mixin(CountOnEveryLayerPlacement.class)
public abstract class CountOnEveryLayerPlacementMixin {

    @WrapOperation(method = "findOnGroundYPosition(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;IIII)I", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/placement/PlacementContext;getMinY()I"))
    private static int farlands$cappedFloor(PlacementContext context, Operation<Integer> original,
            PlacementContext enclosingContext, int xStart, int yStart, int zStart, int layerToPlaceOn) {
        long floor = Math.min((long) WorldBounds.MAX_PLAYABLE_BLOCK,
                Math.max((long) WorldBounds.MIN_PLAYABLE_BLOCK, (long) yStart - FarlandsConfig.maxCapIter));
        return (int) floor;
    }
}
