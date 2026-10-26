package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.NetherForestVegetationFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 下界植被的竖直带宽判据抬到可玩界。
 *
 * <p>
 * {@code place} 有两处读下界：一处是整列的带宽判据，一处是逐格的 {@code finalPos.getY() > getMinY()} 守卫。
 * 两处语义都是「在世界面之上」，一次换界同时覆盖，不需要 ordinal。
 */
@Mixin(NetherForestVegetationFeature.class)
public abstract class NetherForestVegetationFeatureMixin {

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$minY(WorldGenLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMaxY()I"))
    private static int farlands$maxY(WorldGenLevel level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }
}
