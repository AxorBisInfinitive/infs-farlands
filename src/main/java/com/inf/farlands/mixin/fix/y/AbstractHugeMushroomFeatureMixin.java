package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 巨型蘑菇的竖直带宽判据抬到可玩界。
 *
 * <p>
 * {@code isValidPosition} 要求整段菌盖落在 {@code [getMinY() + 1, getMaxY()]} 内，运行期这两个值是 -64 与
 * 319，极端正 Y 上恒假。该方法是抽象类的保护方法，棕与红两种蘑菇都不覆写它，一处换界覆盖两者。
 */
@Mixin(AbstractHugeMushroomFeature.class)
public abstract class AbstractHugeMushroomFeatureMixin {

    @Redirect(method = "isValidPosition(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;ILnet/minecraft/core/BlockPos$MutableBlockPos;Lnet/minecraft/world/level/levelgen/feature/configurations/HugeMushroomFeatureConfiguration;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$minY(WorldGenLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "isValidPosition(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;ILnet/minecraft/core/BlockPos$MutableBlockPos;Lnet/minecraft/world/level/levelgen/feature/configurations/HugeMushroomFeatureConfiguration;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMaxY()I"))
    private static int farlands$maxY(WorldGenLevel level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }
}
