package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ReplaceBlobsFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 团块替换的两处 Y 判据：入口的夹取抬到可玩界，下探改用有界扫描。
 *
 * <p>
 * {@code place} 把原点夹进 {@code [getMinY() + 1, getMaxY()]}，运行期是 -63 到 319，极端 Y 的原点因此被
 * 挪到 319。这里换成可玩界，原点保持原样。
 *
 * <p>
 * 夹取放开之后不能同时放开 {@code findTarget} 的下界：它逐个向下找目标方块，列上没有目标时会一直走到界底，
 * 放开就是十亿级的方块读取。所以那一处改写成「起点往下 {@code maxCapIter} 格」的有界扫描，起点取方法入口
 * 那一刻的光标 Y。
 *
 * <p>
 * 两处必须同批落盘。只放开夹取会让下探从极端 Y 一路走到 -64。
 */
@Mixin(ReplaceBlobsFeature.class)
public abstract class ReplaceBlobsFeatureMixin {

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$minY(WorldGenLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMaxY()I"))
    private static int farlands$maxY(WorldGenLevel level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }

    /**
     * 向下找目标方块，最多往下 {@link FarlandsConfig#maxCapIter} 格。
     *
     * <p>
     * 地板取 {@code clamp(起点 - maxCapIter, 可玩下界 + 1, 可玩上界)}。
     *
     * <p>
     * 两侧都取可玩界。原版下界 -64 加一不能当夹取的一侧：负侧起点在它以下时，它反过来成了地板，循环第一轮
     * 即假。上界夹住是因为起点逼近 int 上界时减法会越界。减法走 long，起点在 int 下界附近时不回绕；结果
     * 不可能超过起点，所以落回 int 是安全的。
     */
    @Overwrite
    private static BlockPos findTarget(LevelAccessor level, BlockPos.MutableBlockPos cursor, Block target) {
        long playableFloor = (long) WorldBounds.MIN_PLAYABLE_BLOCK + 1L;
        long cappedFloor = (long) cursor.getY() - FarlandsConfig.maxCapIter;
        int floor = (int) Math.min((long) WorldBounds.MAX_PLAYABLE_BLOCK, Math.max(playableFloor, cappedFloor));
        while (cursor.getY() > floor) {
            BlockState blockState = level.getBlockState(cursor);
            if (blockState.is(target)) {
                return cursor;
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }
}
