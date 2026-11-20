package com.inf.farlands.mixin.debug.tool.clamp;

import com.inf.farlands.debug.tool.clamp.ClampMode;
import com.inf.farlands.util.pos.ExactPosition;
import love.crysh.long2.ints.Int1024;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 钳制模式的虚拟板：在实体自己的帧里铺一张 1 格厚的平面。
 *
 * <p>
 * 板的 Y 必须与传入的 {@code collisionBox} 同一个帧。锚点非零时 {@code Entity.collide} 传的是局部盒，而
 * {@code entity.getY()} 是世界量级的 double，两者相差一个锚点，板因此永远撞不上。段底取 1280 位值的精确下取整，
 * 顺带解掉另一个退化：世界量级上 ulp 达到 16 之后，{@code floor(y/16)*16} 等于 y 本身，板会跟着脚走。
 */
@Mixin(Entity.class)
public class EntityCollisionMixin {

    /** getEntityCollisions 返回不可变列表，add 虚拟板会抛 UnsupportedOperationException。 */
    @Redirect(method = "collide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<VoxelShape> mutableEntityCollisions(Level level, Entity entity, AABB aabb) {
        return new ArrayList<>(level.getEntityCollisions(entity, aabb));
    }

    @Inject(method = "collideBoundingBox", at = @At("HEAD"))
    private static void addVirtualFloor(@Nullable Entity entity, Vec3 vec,
            AABB collisionBox, Level level, List<VoxelShape> potentialHits,
            CallbackInfoReturnable<Vec3> cir) {
        if (entity instanceof ClampMode clamp && clamp.isClampEnabled() && !entity.isShiftKeyDown()) {
            // potentialHits 可能是不可变列表，add 抛 UnsupportedOperationException。
            // 仅 ArrayList add 虚拟板。
            // 非 ArrayList 的调用路径钳制模式退化为无板。
            if (potentialHits instanceof ArrayList) {
                double floorY = farlands$floorY(entity);
                potentialHits.add(Shapes.create(new AABB(
                        collisionBox.minX - 1.0, floorY - 1.0, collisionBox.minZ - 1.0,
                        collisionBox.maxX + 1.0, floorY, collisionBox.maxZ + 1.0)));
            }
        }
    }

    /**
     * 段底在盒的帧里的 Y。
     *
     * <p>
     * 锚点为零时局部量就是世界量，原式逐位不变；锚点非零时取 1280 位值的精确段底，再减锚点转 double。差落在
     * {@code (-16, 0]}，转 double 无损，板与盒因此同量级。
     */
    @Unique
    private static double farlands$floorY(Entity entity) {
        if (entity instanceof ExactPosition exact && !exact.farlands$anchor(ExactPosition.POS_Y).isZero()) {
            Int1024 sectionBottom = ExactPosition.sectionBottom(exact, ExactPosition.POS_Y);
            return sectionBottom.subtract(exact.farlands$anchor(ExactPosition.POS_Y)).toDouble();
        }
        return Math.floor(entity.getY() / 16.0) * 16.0;
    }
}
