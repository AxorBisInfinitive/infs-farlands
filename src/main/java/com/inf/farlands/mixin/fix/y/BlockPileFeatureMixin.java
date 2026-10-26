package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.BlockPileFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 块堆的地板判据抬到可玩界。
 *
 * <p>
 * {@code place} 在 {@code origin.getY() < getMinY() + 5} 时返回假，运行期下界是 -64，于是 -59 以下的列
 * 放不了块堆。窗口滑到 -64 以下时整片地形都吃这一条。
 */
@Mixin(BlockPileFeature.class)
public abstract class BlockPileFeatureMixin {

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$minY(WorldGenLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }
}
