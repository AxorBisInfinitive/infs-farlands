package com.inf.farlands.mixin.expand.y.border;

import com.inf.farlands.FarlandsConfig;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 玩家在 Y 两端的地板与天花板，形状与 {@code debug.tool.clamp} 那套虚拟板同形。
 *
 * <p>
 * 为什么必须有面：{@code onGround} 的唯一来源是 {@code Entity.move} 里
 * {@code verticalCollisionBelow = verticalCollision && delta.y < 0.0}，即竖直位移被碰撞截断。
 * {@code expand.y.border.PlayerMixin} 的钳制只写回位置，不产生这个截断，所以站在那里没有落地摩擦，
 * 表现为滑冰。两张板让重力位移在平面上被截断，落地、摩擦、跳跃与上台阶都走原版的物理。
 *
 * <p>
 * 平面取钳制平面本身：底面顶面在 {@code ~borderAbsoluteMax + 1}，顶面底面在
 * {@code borderAbsoluteMax - 1}。边界盒那条碰撞面在 {@code borderAbsoluteMax - 16}，比钳制平面高 16 格，
 * 而它的门 {@code WorldBorder.isInsideCloseToBorder} 在钳制平面处不成立，所以那一条到不了这里。
 *
 * <p>
 * 两张板对每个玩家每次 {@code collide} 都加，不按距离开关：没有门就没有可被穿透的窗口，而远处的板不参与
 * {@code Shapes.collide} 的结果，代价只是每次多两个 shape。
 *
 * <p>
 * 注入点选 {@code collide} 里对 {@code collideBoundingBox} 的那次调用，换第五个实参，不 redirect
 * {@code Level.getEntityCollisions}：那条指令已被 {@code debug.tool.clamp.EntityCollisionMixin} 占用，
 * 同一节点不容第二条注入器，先应用的吃掉节点，后应用的一条命中 0 次并在 {@code defaultRequire = 1} 下抛错。
 *
 * <p>
 * 列表自己复制一份再加，不依赖上游那张表可变：上游给的是不可变列表时，直接 add 会抛。
 */
@Mixin(Entity.class)
public class PlayerBoundaryCollisionMixin {

    /** 把碰撞列表换成一份带两块水平面的副本，厚度 1 格，横向按包围盒外扩 1 格。 */
    @ModifyArg(method = "collide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"), index = 4)
    private List<VoxelShape> farlands$withBoundaryFaces(List<VoxelShape> hits) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player)) {
            return hits;
        }
        AABB box = self.getBoundingBox();
        double max = FarlandsConfig.borderAbsoluteMax - 1;
        double min = ~FarlandsConfig.borderAbsoluteMax + 1;
        List<VoxelShape> out = new ArrayList<>(hits.size() + 2);
        out.addAll(hits);
        out.add(Shapes.create(new AABB(box.minX - 1.0, min - 1.0, box.minZ - 1.0,
                box.maxX + 1.0, min, box.maxZ + 1.0)));
        out.add(Shapes.create(new AABB(box.minX - 1.0, max, box.minZ - 1.0,
                box.maxX + 1.0, max + 1.0, box.maxZ + 1.0)));
        return out;
    }
}
