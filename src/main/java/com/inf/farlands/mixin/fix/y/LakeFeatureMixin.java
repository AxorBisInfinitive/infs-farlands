package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.LakeFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 湖的地板判据抬到可玩界。
 *
 * <p>
 * {@code place} 在 {@code origin.getY() <= getMinY() + 4} 时返回假，运行期下界是 -64，于是 -60 及以下
 * 的列放不了湖。与块堆同一类。
 */
@Mixin(LakeFeature.class)
public abstract class LakeFeatureMixin {

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$minY(WorldGenLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }
}
