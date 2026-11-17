package com.inf.farlands.mixin.fix.xyz;

import net.minecraft.util.Mth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code Mth.frac(double)} 改成真正的 double 小数部分。
 *
 * <p>
 * 原版实现是 {@code num - lfloor(num)}，而 {@code lfloor} 就是 {@code (long) Math.floor(v)}，double 到 long 的收窄在
 * 越界时饱和到 {@code Long.MAX_VALUE}。于是 `|num| ≥ 2^63` 之后 `frac` 返回的仍是一个亿亿量级的数，不在 `[0,1)`。
 *
 * <p>
 * 后果落在两段 DDA 上，`BlockGetter.traverseBlocks` 与 `BlockGetter.addCollisionsAlongTravel`：它们用
 * {@code tDelta * (sign > 0 ? 1 - frac : frac)} 定起点，`frac` 一变巨，`t` 就从负的巨数起步，每步只加 `tDelta`
 * 约 0.2，循环要走 `1e307` 步才停，等价于死循环。两处都在渲染线程上，表现为 CPU 高负载与整帧冻结。
 *
 * <p>
 * 判据：`|num| < 2^63` 时两条写法逐位相同，`Math.floor` 的结果转回 double 无损；差异只出现在今天唯一出错的那一段。
 * 全仓 19 处调用都把结果当 `[0,1)` 的分量用，没有一处依赖饱和值。
 */
@Mixin(Mth.class)
public class MthFracMixin {

    @Inject(method = "frac(D)D", at = @At("HEAD"), cancellable = true)
    private static void farlands$frac(double num, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(num - Math.floor(num));
    }
}
