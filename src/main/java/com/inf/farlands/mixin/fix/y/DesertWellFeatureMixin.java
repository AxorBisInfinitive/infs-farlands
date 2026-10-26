package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;
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
 * 地板取 {@code clamp(起点 - maxCapIter, 可玩下界, 可玩上界)}，起点是方法入口那一刻的原点上方一格。
 * 夹住之后最多扫 {@code maxCapIter} 格，找不到就按原来「没沙子」的路径返回假。
 *
 * <p>
 * 两侧都取可玩界。不取原版下界，是因为那是 -64：负侧起点在它以下时，它反过来成了地板，循环第一轮即假，
 * 地物在 -64 以下永远不生成。上界也夹住，是因为循环条件带 {@code + 2}，起点逼近 int 上界时会回绕。
 */
@Mixin(DesertWellFeature.class)
public abstract class DesertWellFeatureMixin {

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$cappedFloor(WorldGenLevel level, Operation<Integer> original,
            FeaturePlaceContext<?> context) {
        long start = (long) context.origin().getY() + 1L;
        long floor = Math.min((long) WorldBounds.MAX_PLAYABLE_BLOCK,
                Math.max((long) WorldBounds.MIN_PLAYABLE_BLOCK, start - FarlandsConfig.maxCapIter));
        return (int) floor;
    }
}
