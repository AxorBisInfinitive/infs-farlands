package com.inf.farlands.client.mixin.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 盒端点的加一改在 double 里做。
 *
 * <p>
 * 原版把 {@code BoundingBox} 的端点直接转给六参重载，其中三个上端点先做 int 的 {@code + 1}。八叉树的根盒
 * 端点由 {@code OctreeMixin} 放到了 {@code INT_MAX}，为的是把可表示段 {@code 134217727} 纳入盒内；那一步之后
 * {@code INT_MAX + 1} 会回绕成 {@code INT_MIN}，盒从构造那刻起就是反序，所有分支恒被判不可见。
 *
 * <p>
 * 用 {@code @Inject} 而不是 {@code @Overwrite}：这个方法在热路径上，别的 mod 也可能在同一处注入，注入能
 * 共存，覆写会把它们顶掉。
 *
 * <p>
 * 对 {@code maxX ≤ INT_MAX - 1} 与 int 版逐位等价，因为 int 到 double 在 2^53 以内无损。
 */
@Mixin(Frustum.class)
public abstract class FrustumMixin {

    /** 目标类里的六参重载，逐字同名同描述符。 */
    @Shadow
    private int cubeInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        throw new AbstractMethodError();
    }

    @Inject(method = "cubeInFrustum(Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)I", at = @At("HEAD"), cancellable = true)
    private void farlands$wideCube(BoundingBox bb, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.cubeInFrustum(bb.minX(), bb.minY(), bb.minZ(),
                (double) bb.maxX() + 1.0, (double) bb.maxY() + 1.0, (double) bb.maxZ() + 1.0));
    }
}
