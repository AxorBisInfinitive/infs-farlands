package com.inf.farlands.client.mixin.render;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 分支盒的 AABB 端点加一改在 double 里做。
 *
 * <p>
 * 与 {@link FrustumMixin} 同一个理由：根盒上端点到 {@code INT_MAX} 之后，int 的 {@code + 1} 会把
 * {@code max} 变成 {@code INT_MIN}，AABB 反序。这里只被 {@code OctreeDebugRenderer} 读取，不影响裁剪，
 * 但反序会让调试线框画到反方向，读数也就没法用。
 *
 * <p>
 * 内部类只能用 {@code targets} 字符串。{@code boundingBox} 是 final 字段，只读，不需要 {@code @Mutable}。
 */
@Mixin(targets = "net.minecraft.client.renderer.Octree$Branch")
public abstract class Octree$BranchMixin {

    @Shadow
    private BoundingBox boundingBox;

    @Inject(method = "getAABB()Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true)
    private void farlands$wideAabb(CallbackInfoReturnable<AABB> cir) {
        cir.setReturnValue(new AABB(
                this.boundingBox.minX(), this.boundingBox.minY(), this.boundingBox.minZ(),
                (double) this.boundingBox.maxX() + 1.0,
                (double) this.boundingBox.maxY() + 1.0,
                (double) this.boundingBox.maxZ() + 1.0));
    }
}
