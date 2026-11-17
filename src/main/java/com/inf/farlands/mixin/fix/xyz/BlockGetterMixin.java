package com.inf.farlands.mixin.fix.xyz;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipBlockStateContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 射线检测在端点出界时直接返回未命中，不进 DDA。
 *
 * <p>
 * `BlockGetter.traverseBlocks` 的循环用 `Mth.frac` 定起点，而 `frac` 的实现是 `v - lfloor(v)`，`lfloor` 返回 long，
 * 在 `|v| > 9.22e18` 时饱和。`1e307` 处 `frac` 约为 `1e307`，于是 `1 - frac` 是负的一亿亿亿量级数，`t` 从负数起步，
 * 而每步只加 `tDelta` 约 0.2，`while (t <= 1)` 要走 `1e307` 步才停，等价于死循环；每步还会查一次方块与形状，所以
 * CPU 烧在 `getShape` 上。
 *
 * <p>
 * 只有往外走的那一侧会卡：正坐标朝正方向、负坐标朝负方向时 `t` 拿到负的巨数，反方向拿到正的巨数，循环直接不进。
 * 所以同一位置卡不卡取决于朝向。
 *
 * <p>
 * vanilla 的方块只在 int 内存在，端点出界时 DDA 走的是被 `Mth.floor` 饱和过的格号，本来就无意义，返回未命中与
 * 「那里没有方块」一致。两个入口都是 `traverseBlocks` 的调用方，一处一个注入，守卫放在入口而不是循环里。
 */
@Mixin(BlockGetter.class)
public interface BlockGetterMixin {

    @Inject(method = "clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;", at = @At("HEAD"), cancellable = true)
    default void farlands$skipSaturatedClip(ClipContext context, CallbackInfoReturnable<BlockHitResult> cir) {
        if (farlands$saturated(context.getFrom(), context.getTo())) {
            cir.setReturnValue(farlands$miss(context.getFrom(), context.getTo()));
        }
    }

    @Inject(method = "isBlockInLine(Lnet/minecraft/world/level/ClipBlockStateContext;)Lnet/minecraft/world/phys/BlockHitResult;", at = @At("HEAD"), cancellable = true)
    default void farlands$skipSaturatedLine(ClipBlockStateContext context,
            CallbackInfoReturnable<BlockHitResult> cir) {
        if (farlands$saturated(context.getFrom(), context.getTo())) {
            cir.setReturnValue(farlands$miss(context.getFrom(), context.getTo()));
        }
    }

    /** 两头任一出界即为饱和。 */
    @Unique
    private static boolean farlands$saturated(Vec3 from, Vec3 to) {
        return !WorldBounds.inBlockAbsolute(from) || !WorldBounds.inBlockAbsolute(to);
    }

    /** 与 vanilla 两个入口各自的 missFactory 同式。 */
    @Unique
    private static BlockHitResult farlands$miss(Vec3 from, Vec3 to) {
        Vec3 delta = from.subtract(to);
        return BlockHitResult.miss(to, Direction.getApproximateNearest(delta.x, delta.y, delta.z),
                BlockPos.containing(to));
    }
}
