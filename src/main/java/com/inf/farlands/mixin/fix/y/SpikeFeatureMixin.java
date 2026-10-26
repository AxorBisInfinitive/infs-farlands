package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 冰刺的向下找地面改成有界扫描。
 *
 * <p>
 * {@code place} 从原点起逐格向下找可站立方块，走到 {@code getMinY() + 2} 为止，运行期是 -62。空列上
 * 极端 Y 会一路走到底。
 *
 * <p>
 * 地板取 {@code clamp(起点 - maxCapIter, 可玩下界, 可玩上界)}，起点就是方法入口的原点。
 *
 * <p>
 * 两侧都取可玩界，理由与沙井同：原版下界 -64 在负侧起点以下会反过来成为地板，上界夹住是因为循环条件带
 * {@code + 2}。
 */
@Mixin(SpikeFeature.class)
public abstract class SpikeFeatureMixin {

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$cappedFloor(WorldGenLevel level, Operation<Integer> original,
            FeaturePlaceContext<?> context) {
        long start = (long) context.origin().getY();
        long floor = Math.min((long) WorldBounds.MAX_PLAYABLE_BLOCK,
                Math.max((long) WorldBounds.MIN_PLAYABLE_BLOCK, start - FarlandsConfig.maxCapIter));
        return (int) floor;
    }
}
