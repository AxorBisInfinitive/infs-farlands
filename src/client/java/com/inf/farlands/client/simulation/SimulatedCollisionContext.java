package com.inf.farlands.client.simulation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.EntityCollisionContext;

/**
 * 模拟层的碰撞上下文：实体脚部高度已经折算到段内局部坐标。
 *
 * <p>
 * {@code EntityCollisionContext.isAbove} 的比较式是 {@code entityBottom > pos.getY() + shape.max(Y)}，vanilla 里
 * 两侧都是世界量。模拟层的 {@code pos} 是段内局部坐标，所以调用方必须先把 {@code entityBottom} 折算成本段的局部量，
 * 否则该比较式在任何量级上恒真。折算在 {@code CollisionGetterMixin} 里做：锚点非零时用
 * {@code localBottomY - (段原点 - 锚点)}，锚点为零时用 {@code 段原点}。
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

    /**
     * 入参 {@code entityBottom} 已经是段内局部量，本类不再做任何坐标折算。
     *
     * <p>
     * 折算不能放在这里由 {@code source.getY()} 现减：帧的锚点非零时那个值是 double 投影，误差随量级增长，减出来的
     * 局部量已经不是本段的那一格。
     */
    public SimulatedCollisionContext(Entity source, double entityBottom) {
        super(source.isDescending(), false, entityBottom,
                source instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY,
                false, source);
    }
}
