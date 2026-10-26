package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 树放置的竖直带宽判据抬到可玩界。
 *
 * <p>
 * vanilla 的 {@code doPlace} 要求整段树落在 {@code [getMinY() + 1, getMaxY() + 1]} 里。本 port 运行期的
 * 这两个值仍是维度的 -64 与 319，于是 y 超过 320 的列上树一律返回假，{@code /place feature} 与装饰两条路
 * 都放不下。判据只读这两个访问器，换界即可，原式的 {@code + 1} 不动。
 *
 * <p>
 * 顶端仍是特征自己的算术：{@code origin.y + treeHeight + 1} 在 y 接近 2147483638 时会回绕，换界改不到
 * 那一段。
 */
@Mixin(TreeFeature.class)
public abstract class TreeFeatureMixin {

    @Redirect(method = "doPlace(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;Lnet/minecraft/world/level/levelgen/feature/foliageplacers/FoliagePlacer$FoliageSetter;Lnet/minecraft/world/level/levelgen/feature/configurations/TreeConfiguration;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$minY(WorldGenLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "doPlace(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;Lnet/minecraft/world/level/levelgen/feature/foliageplacers/FoliagePlacer$FoliageSetter;Lnet/minecraft/world/level/levelgen/feature/configurations/TreeConfiguration;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMaxY()I"))
    private static int farlands$maxY(WorldGenLevel level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }
}
