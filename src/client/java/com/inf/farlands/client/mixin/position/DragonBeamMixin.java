package com.inf.farlands.client.mixin.position;

import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 龙与水晶之间的光束偏移改在帧里算。
 *
 * <p>
 * 原式是两个世界量级位置相减，{@code 2^60} 处 ulp 是 256，差值只剩 0 或 256，光束要么没有要么指错方向。
 * {@code beamOffset} 本来就是差值，消费端 {@code EnderDragonRenderer.submitCrystalBeams} 只读三个分量，所以
 * 只需在抽取的返回处重算一次，消费端不动。
 *
 * <p>
 * 不判量级：两端各取锚点与局部量，{@code FrameMath.offset} 在锚点为 0 时退化成原版那一次减法，所以原版有定义
 * 的坐标范围内逐位相同，之上只有精度的渐变。水晶的竖直偏移
 * {@code EndCrystalRenderer.getY} 是纯函数，照原样加在 y 上。
 */
@Mixin(EnderDragonRenderer.class)
public class DragonBeamMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;F)V", at = @At("RETURN"))
    private void farlands$frameBeam(EnderDragon entity, EnderDragonRenderState state, float partialTicks,
            CallbackInfo ci) {
        EndCrystal crystal = entity.nearestCrystal;
        if (crystal == null || !(entity instanceof ExactPosition dragon)
                || !(crystal instanceof ExactPosition beamTarget)) {
            return;
        }
        long dragonX = dragon.farlands$anchor(ExactPosition.POS_X);
        long dragonY = dragon.farlands$anchor(ExactPosition.POS_Y);
        long dragonZ = dragon.farlands$anchor(ExactPosition.POS_Z);
        long crystalX = beamTarget.farlands$anchor(ExactPosition.POS_X);
        long crystalY = beamTarget.farlands$anchor(ExactPosition.POS_Y);
        long crystalZ = beamTarget.farlands$anchor(ExactPosition.POS_Z);
        double dx = FrameMath.offset(crystalX, beamTarget.farlands$lerpLocal(ExactPosition.POS_X, partialTicks),
                dragonX, dragon.farlands$lerpLocal(ExactPosition.POS_X, partialTicks));
        double dy = FrameMath.offset(crystalY, beamTarget.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks),
                dragonY, dragon.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks));
        double dz = FrameMath.offset(crystalZ, beamTarget.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks),
                dragonZ, dragon.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks));
        state.beamOffset = new Vec3(dx, dy + EndCrystalRenderer.getY(crystal.time + partialTicks), dz);
    }
}
