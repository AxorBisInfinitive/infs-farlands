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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
 * 遍历照抄 vanilla {@code BlockCollisions}：查询盒每轴外扩 1 格、容差 {@code 1.0E-7}；角格跳过，面格只测
 * {@code hasLargeCollisionShape()} 的方块，边格只测 {@code MOVING_PISTON}。每格取
 * {@code state.getCollisionShape}，整块走 {@code Shapes.block()} 的盒判交快路径，其余用
 * {@code Shapes.joinIsNotEmpty} 精确判交。不再有格并集一类的粗筛，判定不依赖任何形状范围假设。
 *
 * <p>
 * 格坐标是 long，`Cursor3D` 与 `BlockPos` 都装不下，所以类型计数与三轴循环在这里就地展开，公式与
 * {@code Cursor3D.getNextType} 相同。段与它的 getter、上下文只在跨段时重建，等同 vanilla 的 chunk 级缓存。
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
        if (simulation.snapshot().sections().isEmpty()) {
            return;
        }
        long x0 = Mth.lfloor(box.minX - 1.0E-7) - 1L;
        long x1 = Mth.lfloor(box.maxX + 1.0E-7) + 1L;
        long y0 = Mth.lfloor(box.minY - 1.0E-7) - 1L;
        long y1 = Mth.lfloor(box.maxY + 1.0E-7) + 1L;
        long z0 = Mth.lfloor(box.minZ - 1.0E-7) - 1L;
        long z1 = Mth.lfloor(box.maxZ + 1.0E-7) + 1L;
        VoxelShape entityShape = Shapes.create(box);
        List<VoxelShape> hits = null;
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        boolean hasSection = false;
        long sectionX = 0L;
        long sectionY = 0L;
        long sectionZ = 0L;
        SimulationSection section = null;
        SectionGetter getter = null;
        CollisionContext context = null;
        for (long x = x0; x <= x1; x++) {
            for (long y = y0; y <= y1; y++) {
                for (long z = z0; z <= z1; z++) {
                    int type = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0)
                            + (z == z0 || z == z1 ? 1 : 0);
                    if (type == 3) {
                        continue;
                    }
                    long sx = Math.floorDiv(x, (long) SimulationSection.SIZE);
                    long sy = Math.floorDiv(y, (long) SimulationSection.SIZE);
                    long sz = Math.floorDiv(z, (long) SimulationSection.SIZE);
                    if (!hasSection || sx != sectionX || sy != sectionY || sz != sectionZ) {
                        hasSection = true;
                        sectionX = sx;
                        sectionY = sy;
                        sectionZ = sz;
                        section = simulation.sectionOrNull(sx, sy, sz);
                        getter = section == null ? null : new SectionGetter(section, simulation);
                        context = section == null ? null
                                : source == null ? CollisionContext.empty()
                                        : new SimulatedCollisionContext(source, sy * SimulationSection.SIZE);
                    }
                    if (section == null) {
                        continue;
                    }
                    int localX = (int) (x - sx * SimulationSection.SIZE);
                    int localY = (int) (y - sy * SimulationSection.SIZE);
                    int localZ = (int) (z - sz * SimulationSection.SIZE);
                    SimulationBlock block = section.blockAt(localX, localY, localZ);
                    if (block == null) {
                        continue;
                    }
                    BlockState state = block.state();
                    if (type == 1 && !state.hasLargeCollisionShape()) {
                        continue;
                    }
                    if (type == 2 && !state.is(Blocks.MOVING_PISTON)) {
                        continue;
                    }
                    localPos.set(localX, localY, localZ);
                    VoxelShape shape = block.collisionShape();
                    if (shape == null) {
                        shape = state.getCollisionShape(getter, localPos, context);
                    }
                    VoxelShape moved = shape.move((double) x, (double) y, (double) z);
                    if (shape == Shapes.block()) {
                        if (!box.intersects((double) x, (double) y, (double) z,
                                (double) x + 1.0, (double) y + 1.0, (double) z + 1.0)) {
                            continue;
                        }
                    } else if (moved.isEmpty() || !Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) {
                        continue;
                    }
                    if (hits == null) {
                        hits = new ArrayList<>();
                    }
                    hits.add(moved);
                }
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
