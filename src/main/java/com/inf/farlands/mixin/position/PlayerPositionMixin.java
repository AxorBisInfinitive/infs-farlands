package com.inf.farlands.mixin.position;

import com.inf.farlands.util.pos.ExactCollisionSource;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 潜行边缘保护的碰撞探测改在帧里做。
 *
 * <p>
 * {@code Player.canFallAtLeast} 用 {@code getBoundingBox()} 造探测盒再问 {@code level.noCollision}，而
 * {@code getBoundingBox()} 是世界量级的盒：{@code 5e18} 处六个端点塌成一个点，方块查询找不到东西，于是恒返回
 * 「可以下坠」。上游的 {@code maybeBackOffFromEdge} 因此把水平位移每 0.05 格减一次，直到减成 0，表现为潜行时
 * 水平移动被卡住。
 *
 * <p>
 * 锚点全零时保持原式，逐位不变；锚点非零时探测盒改用局部量生成，判定交给帧内碰撞源，即客户端的模拟层。
 * 服务端没有模拟层，那里按「无方块」处理，与改动前一致。
 */
@Mixin(Player.class)
public abstract class PlayerPositionMixin {

    @Overwrite
    private boolean canFallAtLeast(double deltaX, double deltaZ, double minHeight) {
        Player self = (Player) (Object) this;
        ExactPosition holder = (ExactPosition) (Object) this;
        Level level = self.level();
        boolean framed = holder.farlands$anchor(ExactPosition.POS_X) != 0L
                || holder.farlands$anchor(ExactPosition.POS_Y) != 0L
                || holder.farlands$anchor(ExactPosition.POS_Z) != 0L;
        if (!framed) {
            AABB boundingBox = self.getBoundingBox();
            return level.noCollision(self, new AABB(
                    boundingBox.minX + 1.0E-7 + deltaX,
                    boundingBox.minY - minHeight - 1.0E-7,
                    boundingBox.minZ + 1.0E-7 + deltaZ,
                    boundingBox.maxX - 1.0E-7 + deltaX,
                    boundingBox.minY,
                    boundingBox.maxZ - 1.0E-7 + deltaZ));
        }
        AABB local = holder.farlands$localBox();
        AABB probe = new AABB(
                local.minX + 1.0E-7 + deltaX,
                local.minY - minHeight - 1.0E-7,
                local.minZ + 1.0E-7 + deltaZ,
                local.maxX - 1.0E-7 + deltaX,
                local.minY,
                local.maxZ - 1.0E-7 + deltaZ);
        if (level instanceof ExactCollisionSource source) {
            return source.farlands$frameCollisions(self, probe).isEmpty();
        }
        return true;
    }
}
