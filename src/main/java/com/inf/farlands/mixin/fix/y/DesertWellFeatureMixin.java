package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.FarlandsConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.DesertWellFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 沙井的向下找沙改成有界扫描。
 *
 * <p>
 * {@code place} 从原点上方一格起逐格向下找沙子，走到 {@code getMinY() + 2} 为止。运行期下界是 -64，列上
 * 没有沙子时这就是从原点一路走到 -62，极端 Y 上是十亿级读取。
 *
 * <p>
 * 地板取 {@code max(原下界, 起点 - maxCapIter)}，起点是方法入口那一刻的原点上方一格。夹住之后最多扫
 * {@code maxCapIter} 格，找不到就按原来「没沙子」的路径返回假。
 */
@Mixin(DesertWellFeature.class)
public abstract class DesertWellFeatureMixin {

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$cappedFloor(WorldGenLevel level, Operation<Integer> original,
            FeaturePlaceContext<?> context) {
        long start = (long) context.origin().getY() + 1L;
        return (int) Math.max((long) original.call(level), start - FarlandsConfig.maxCapIter);
    }
}
