package com.inf.farlands.client.mixin.simulation;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Iterables;

import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationBlock;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.client.simulation.SimulationSection;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把模拟方块层的碰撞形状并进方块碰撞取数。
 *
 * <p>
 * 挂在 {@code getBlockCollisions} 的返回处：它是实体移动取方块碰撞的唯一公共入口，
 * {@code Entity.collide} 经 {@code collectColliders} 走到这里；而服务端那条
 * {@code isEntityCollidingWithAnythingNew} 走的是 {@code getPreMoveCollisions}，直接调私有的
 * {@code getBlockCollisionsFromContext}，绕过本方法。所以本注入放客户端源集时，服务端结构上拿不到。
 *
 * <p>
 * 两级过滤：先拿段局部包围盒与查询盒在段坐标里判交，不相交整段跳过；相交再逐块用
 * {@code shape.bounds()} 判交，只把命中的那几块的形状合并成一个。合并推迟到命中之后，成本与段大小脱钩，
 * 这是 {@code Shapes.or} 逐次合并的代价决定的。
 */
@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {

    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void farlands$simulationCollision(Entity source, AABB box,
            CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        Simulation.Snapshot snapshot = ((SimulationHolder) Minecraft.getInstance()).simulation().snapshot();
        List<Simulation.SnapSection> sections = snapshot.sections();
        if (sections.isEmpty()) {
            return;
        }
        List<VoxelShape> hits = null;
        for (Simulation.SnapSection section : sections) {
            double originX = (double) section.originX();
            double originY = (double) section.originY();
            double originZ = (double) section.originZ();
            AABB sectionBox = new AABB(originX, originY, originZ,
                    originX + SimulationSection.SIZE, originY + SimulationSection.SIZE,
                    originZ + SimulationSection.SIZE);
            if (!box.intersects(sectionBox)) {
                continue;
            }
            AABB local = box.move(-originX, -originY, -originZ);
            if (!local.intersects(section.localBounds())) {
                continue;
            }
            SimulationSection live = section.section();
            int occupied = live.occupiedCount();
            for (int i = 0; i < occupied; i++) {
                int index = live.occupiedIndex(i);
                int localX = (index >> 8) & 15;
                int localY = (index >> 4) & 15;
                int localZ = index & 15;
                SimulationBlock block = live.blockAt(localX, localY, localZ);
                if (block == null) {
                    continue;
                }
                VoxelShape shape = block.shape();
                AABB blockBounds = shape.bounds().move(localX, localY, localZ);
                if (!local.intersects(blockBounds)) {
                    continue;
                }
                if (hits == null) {
                    hits = new ArrayList<>();
                }
                hits.add(shape.move(localX, localY, localZ).move(originX, originY, originZ));
            }
        }
        if (hits == null) {
            return;
        }
        VoxelShape merged = hits.size() == 1 ? hits.get(0)
                : Shapes.or(hits.get(0), hits.subList(1, hits.size()).toArray(new VoxelShape[0]));
        cir.setReturnValue(Iterables.concat(cir.getReturnValue(), List.of(merged)));
    }
}
