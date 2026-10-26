package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.feature.BasaltColumnsFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 玄武岩柱的上下界抬到可玩界。
 *
 * <p>
 * {@code findSurface} 向下找可放置面，{@code findAir} 向上找空位。运行期下界是 -64，上界是 319，于是
 * 极端正 Y 上 {@code findAir} 第一轮条件就不成立，柱子在 -64 以下也找不到面。
 *
 * <p>
 * 换界不会变成长扫：两条循环各自有 {@code limit}，那个值是列内曼哈顿距离，{@code reach} 上限 8，所以最多
 * 十六步。
 */
@Mixin(BasaltColumnsFeature.class)
public abstract class BasaltColumnsFeatureMixin {

    @Redirect(method = "findSurface(Lnet/minecraft/world/level/LevelAccessor;ILnet/minecraft/core/BlockPos$MutableBlockPos;I)Lnet/minecraft/core/BlockPos;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMinY()I"))
    private static int farlands$minY(LevelAccessor level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "findAir(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos$MutableBlockPos;I)Lnet/minecraft/core/BlockPos;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMaxY()I"))
    private static int farlands$maxY(LevelAccessor level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }
}
