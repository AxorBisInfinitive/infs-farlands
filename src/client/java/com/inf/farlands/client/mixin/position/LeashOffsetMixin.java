package com.inf.farlands.client.mixin.position;

import java.util.List;

import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拴绳两端改存「零与差值」。
 *
 * <p>
 * 消费端 {@code LeashFeatureRenderer.renderLeash} 只取 {@code end - start}，两端各自的绝对量从不使用。所以把
 * {@code start} 写成 {@code Vec3.ZERO}、{@code end} 写成帧差，消费端一个字节都不用改，几何仍从实体位姿出发。
 * 这是有意的语义改写：字段从「世界位置」变成「零与差值」，仓库内只有那一个消费点。
 *
 * <p>
 * 四绳与单绳两支都要重算：单绳的终点是 {@code getRopeHoldPosition}，即位置加眼高的 0.7 倍；四绳的终点是持有者
 * 位置加 {@code getQuadLeashHolderOffsets} 的第 i 项按身体朝向旋转。起点是实体位置加 {@code LeashState.offset}，
 * 那个偏移 vanilla 已经写好，直接读。
 *
 * <p>
 * 逐条独立：某一条拿不到精确位置时跳过它，保留 vanilla 当帧刚写的世界值，不影响其余各条。
 */
@Mixin(EntityRenderer.class)
public class LeashOffsetMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("RETURN"))
    private void farlands$frameLeashes(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        List<EntityRenderState.LeashState> leashes = state.leashStates;
        if (leashes == null || leashes.isEmpty()) {
            return;
        }
        if (!(entity instanceof Leashable leashable) || !(entity instanceof ExactPosition holder)) {
            return;
        }
        Entity roper = leashable.getLeashHolder();
        if (!(roper instanceof ExactPosition roperHolder)) {
            return;
        }
        long entityX = holder.farlands$anchor(ExactPosition.POS_X);
        long entityY = holder.farlands$anchor(ExactPosition.POS_Y);
        long entityZ = holder.farlands$anchor(ExactPosition.POS_Z);
        long roperX = roperHolder.farlands$anchor(ExactPosition.POS_X);
        long roperY = roperHolder.farlands$anchor(ExactPosition.POS_Y);
        long roperZ = roperHolder.farlands$anchor(ExactPosition.POS_Z);
        boolean quad = roper.supportQuadLeashAsHolder() && leashable.supportQuadLeash();
        Vec3[] roperOffsets = quad ? roper.getQuadLeashHolderOffsets() : null;
        float roperYRot = quad
                ? roper.getPreciseBodyRotation(partialTicks) * (float) (Math.PI / 180.0)
                : 0.0F;
        double ropeHoldY = (double) roper.getEyeHeight() * 0.7;
        double entityLocalX = holder.farlands$lerpLocal(ExactPosition.POS_X, partialTicks);
        double entityLocalY = holder.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks);
        double entityLocalZ = holder.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks);
        double roperLocalX = roperHolder.farlands$lerpLocal(ExactPosition.POS_X, partialTicks);
        double roperLocalY = roperHolder.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks);
        double roperLocalZ = roperHolder.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks);
        for (int i = 0; i < leashes.size(); i++) {
            EntityRenderState.LeashState leash = leashes.get(i);
            double endX = 0.0;
            double endY = ropeHoldY;
            double endZ = 0.0;
            if (roperOffsets != null) {
                if (i >= roperOffsets.length) {
                    continue;
                }
                Vec3 rotated = roperOffsets[i].yRot(-roperYRot);
                endX = rotated.x;
                endY = rotated.y;
                endZ = rotated.z;
            }
            double startLocalX = entityLocalX + leash.offset.x;
            double startLocalY = entityLocalY + leash.offset.y;
            double startLocalZ = entityLocalZ + leash.offset.z;
            leash.start = Vec3.ZERO;
            leash.end = new Vec3(
                    FrameMath.offset(roperX, roperLocalX + endX, entityX, startLocalX),
                    FrameMath.offset(roperY, roperLocalY + endY, entityY, startLocalY),
                    FrameMath.offset(roperZ, roperLocalZ + endZ, entityZ, startLocalZ));
        }
    }
}
