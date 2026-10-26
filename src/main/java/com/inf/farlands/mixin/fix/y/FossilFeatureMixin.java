package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FossilFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 化石的目标盒与地板判据抬到可玩界。
 *
 * <p>
 * 两处读下界：一处是裁剪盒的底，一处是把目标 Y 夹在 {@code getMinY() + 10} 之上；一处读上界，是裁剪盒的顶。
 * 运行期这两个值是 -64 与 319，盒因此只覆盖维度那一段，极端 Y 的化石写不进去。
 *
 * <p>
 * 加宽后的盒只当裁剪用：这条链上的消费者只用 {@code isInside} 与 {@code forAllCorners}，不读
 * {@code getYSpan}。跨度在可玩界上会溢出成负数，所以这块盒不许传出去。
 */
@Mixin(FossilFeature.class)
public abstract class FossilFeatureMixin {

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$minY(WorldGenLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMaxY()I"))
    private static int farlands$maxY(WorldGenLevel level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }
}
