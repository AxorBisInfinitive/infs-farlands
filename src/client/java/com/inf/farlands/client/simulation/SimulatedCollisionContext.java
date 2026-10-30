package com.inf.farlands.client.simulation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.EntityCollisionContext;

/**
 * 模拟层的碰撞上下文：把实体脚部的高度折算到段内局部坐标。
 *
 * <p>
 * {@code EntityCollisionContext.isAbove} 的比较式是 {@code entityBottom > pos.getY() + shape.max(Y)}，vanilla 里
 * 两侧都是世界量。模拟层的 {@code pos} 是段内局部坐标，所以必须把 {@code entityBottom} 减去段原点，否则该比较式
 * 在 {@code 2^40} 处恒真。
 *
 * <p>
 * 必须是 {@code EntityCollisionContext} 的子类而不是直接实现 {@code CollisionContext}：vanilla 有两处把上下文
 * 降型成本类，{@code LiquidBlock} 与 {@code PowderSnowBlock}，直接实现会走另一支。
 *
 * <p>
 * 矿车特例不复刻：{@code CollisionContext.of} 对开启实验移动的矿车用 {@code MinecartCollisionContext}，而它按世界
 * 坐标读轨道状态，在局部坐标里没有等价的取值。这里按普通上下文处理，差异只落在矿车与轨道。
 */
public final class SimulatedCollisionContext extends EntityCollisionContext {

    public SimulatedCollisionContext(Entity source, long sectionOriginY) {
        super(source.isDescending(), false, source.getY() - (double) sectionOriginY,
                source instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY,
                false, source);
    }
}
