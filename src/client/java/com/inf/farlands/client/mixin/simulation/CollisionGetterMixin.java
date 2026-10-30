package com.inf.farlands.client.mixin.simulation;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Iterables;

import com.inf.farlands.client.simulation.SectionGetter;
import com.inf.farlands.client.simulation.SimulatedCollisionContext;
import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationBlock;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.client.simulation.SimulationSection;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
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
 * {@code Entity.collide} 经 {@code collideBoundingBox} 与 {@code collectColliders} 走到这里。服务端那条
 * {@code isEntityCollidingWithAnythingNew} 走的是 {@code getPreMoveCollisions}，直接调私有的
 * {@code getBlockCollisionsFromContext}，绕过本方法；但那只绕开了「新增碰撞校验」一项。
 *
 * <p>
 * 本方法是 {@code CollisionGetter} 的 default，单机的集成服务端与客户端同进程，{@code ServerLevel} 的实体
 * 移动同样进来；{@code PathNavigationRegion} 也实现本接口。所以处理体第一行把接收者限定住：先判逻辑侧，
 * 再判它是不是 {@link Simulation#level()} 记下的那个客户端关卡。源集目录只决定本类在哪个进程加载，挡不住
 * 同进程的服务端。
 *
 * <p>
 * 闸门之后，处理体只在客户端线程、只对那个 ClientLevel 运行，段容器的无锁读写因此成立。
 *
 * <p>
 * 形状来源与 vanilla 同：块上有覆盖就用它，否则走 {@code state.getCollisionShape}，与
 * {@code BlockCollisions} 的入口一致；上下文是 {@link SimulatedCollisionContext}，把实体脚部折到段内局部
 * 坐标，否则 {@code isAbove} 在 {@code 2^40} 处恒真。空形状跳过，判交用 {@code Shapes.joinIsNotEmpty}，与
 * vanilla 一样只把真正接触的形状并入。
 *
 * <p>
 * 粗筛两级：段立方与查询盒判交，再用快照里的格并集包围盒判交。两者都是「形状落在格内」这一不变式下的超集，
 * 所以任何一级都不会误剪。
 */
@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {

    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void farlands$simulationCollision(Entity source, AABB box,
            CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        if (!((Object) this instanceof Level level && level.isClientSide())) {
            return;
        }
        Simulation simulation = ((SimulationHolder) Minecraft.getInstance()).simulation();
        if (simulation.level() != this) {
            return;
        }
        Simulation.Snapshot snapshot = simulation.snapshot();
        List<Simulation.SnapSection> sections = snapshot.sections();
        if (sections.isEmpty()) {
            return;
        }
        List<VoxelShape> hits = null;
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
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
            VoxelShape entityShape = Shapes.create(local);
            SimulationSection live = section.section();
            SectionGetter getter = new SectionGetter(live, simulation);
            CollisionContext context = source == null ? CollisionContext.empty()
                    : new SimulatedCollisionContext(source, section.originY());
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
                localPos.set(localX, localY, localZ);
                VoxelShape shape = block.collisionShape();
                if (shape == null) {
                    shape = block.state().getCollisionShape(getter, localPos, context);
                }
                if (shape.isEmpty()) {
                    continue;
                }
                VoxelShape atLocal = shape.move(localX, localY, localZ);
                if (!Shapes.joinIsNotEmpty(atLocal, entityShape, BooleanOp.AND)) {
                    continue;
                }
                if (hits == null) {
                    hits = new ArrayList<>();
                }
                hits.add(shape.move(originX + localX, originY + localY, originZ + localZ));
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
