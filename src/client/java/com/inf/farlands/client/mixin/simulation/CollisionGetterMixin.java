package com.inf.farlands.client.mixin.simulation;

import java.util.List;

import com.google.common.collect.Iterables;
import com.inf.farlands.client.simulation.FixedBlock;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把固定方块的碰撞形状并进方块碰撞取数。
 *
 * <p>
 * 挂在 {@code getBlockCollisions} 的返回处：它是实体移动取方块碰撞的唯一公共入口，
 * {@code Entity.collide} 经 {@code collectColliders} 走到这里；而服务端那条
 * {@code isEntityCollidingWithAnythingNew} 走的是 {@code getPreMoveCollisions}，直接调私有的
 * {@code getBlockCollisionsFromContext}，绕过本方法。所以本注入放客户端源集时，服务端结构上拿不到这块。
 *
 * <p>
 * 只在查询盒与本块包围盒相交时并进去：静止站在块顶时两盒只贴面，{@code AABB.intersects} 的严格不等号
 * 判为不相交，形状不会被反复取出；下落时调用方已按位移扩张过查询盒，相交成立，竖直位移被截断在块顶。
 */
@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {

    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void farlands$fixedBlockCollision(Entity source, AABB box,
            CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        if (box.intersects(FixedBlock.BOX)) {
            cir.setReturnValue(Iterables.concat(cir.getReturnValue(), List.of(FixedBlock.SHAPE)));
        }
    }
}
