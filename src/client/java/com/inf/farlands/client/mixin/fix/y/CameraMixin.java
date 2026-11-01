package com.inf.farlands.client.mixin.fix.y;

import java.util.Arrays;
import java.util.List;

import com.inf.farlands.client.render.ExactCameraOrigin;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机原点的帧表示，以及流体雾判据不落 float。
 *
 * <p>
 * 相机原点这一段：{@code alignWithEntity} 把位置写成世界量级的 double，{@code 5e18} 处一个 ulp 是 1024，
 * 眼高 1.62 与第三人称后退几格都会被吞掉。这里在同一方法的头尾各挂一条注入，另在 {@code move} 的头部截获
 * 位移，产出「锚点 long 加局部 double」，供模拟层的剔除、段 UBO、选择框使用。
 *
 * <p>
 * 眼高用相机自己的 {@code eyeHeightOld} 与 {@code eyeHeight} 插值，与 vanilla 同源；位移必须复刻
 * {@code move} 的算式 {@code new Vector3f(right, up, -forwards).rotate(rotation())}，运算顺序逐字照抄，
 * 否则局部量会与相机自己算的位置差一位。
 *
 * <p>
 * 矿车分支走 {@code behavior.getCartLerpPosition}，那几个减法在世界量级上做，精度已经丢了，帧化不了，
 * 那里把 {@code farlands$cameraValid} 置假，消费侧退回相机自己的 double 位置。
 *
 * <p>
 * 流体雾判据那一段是既有改动：vanilla 把方块 Y 收窄成 float 再加流体面高度，本 port 的 Y 到 ±2^31 时
 * float 间距 128，面高被吞掉。判据与 vanilla 逐字一致，只把两处比较换成 double。
 */
@Mixin(Camera.class)
public abstract class CameraMixin implements ExactCameraOrigin {

    @Shadow
    private boolean initialized;

    @Shadow
    private Level level;

    @Shadow
    private Vec3 position;

    @Shadow
    private Entity entity;

    @Shadow
    private float eyeHeight;

    @Shadow
    private float eyeHeightOld;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos blockPosition;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract Camera.NearPlane getNearPlane(float fov);

    @Shadow
    public abstract Quaternionf rotation();

    @Unique
    private long farlands$cameraAnchorX;

    @Unique
    private long farlands$cameraAnchorY;

    @Unique
    private long farlands$cameraAnchorZ;

    @Unique
    private double farlands$cameraLocalX;

    @Unique
    private double farlands$cameraLocalY;

    @Unique
    private double farlands$cameraLocalZ;

    @Unique
    private boolean farlands$cameraValid;

    @Unique
    private double farlands$moveX;

    @Unique
    private double farlands$moveY;

    @Unique
    private double farlands$moveZ;

    @Unique
    private boolean farlands$minecartFrame;

    @Override
    public boolean farlands$cameraValid() {
        return this.farlands$cameraValid;
    }

    @Override
    public long farlands$cameraAnchor(int axis) {
        if (axis == ExactPosition.POS_X) {
            return this.farlands$cameraAnchorX;
        }
        return axis == ExactPosition.POS_Y ? this.farlands$cameraAnchorY : this.farlands$cameraAnchorZ;
    }

    @Override
    public double farlands$cameraLocal(int axis) {
        if (axis == ExactPosition.POS_X) {
            return this.farlands$cameraLocalX;
        }
        return axis == ExactPosition.POS_Y ? this.farlands$cameraLocalY : this.farlands$cameraLocalZ;
    }

    /** 复位累加量与标记，并记下走的是不是帧化不了的矿车分支。 */
    @Inject(method = "alignWithEntity(F)V", at = @At("HEAD"))
    private void farlands$resetCameraFrame(float partialTicks, CallbackInfo ci) {
        this.farlands$moveX = 0.0;
        this.farlands$moveY = 0.0;
        this.farlands$moveZ = 0.0;
        this.farlands$cameraValid = false;
        this.farlands$minecartFrame = this.entity != null && this.entity.isPassenger()
                && this.entity.getVehicle() instanceof Minecart minecart
                && minecart.getBehavior() instanceof NewMinecartBehavior behavior
                && behavior.cartHasPosRotLerp();
    }

    /**
     * 截获相机的位移。实参不是位移，{@code move} 会先旋转再相加，这里照同一顺序算一遍。
     */
    @Inject(method = "move(FFF)V", at = @At("HEAD"))
    private void farlands$accumulateMove(float forwards, float up, float right, CallbackInfo ci) {
        Vector3f offset = new Vector3f(right, up, -forwards).rotate(this.rotation());
        this.farlands$moveX += offset.x;
        this.farlands$moveY += offset.y;
        this.farlands$moveZ += offset.z;
    }

    /** 在方法尾把最终相机位置折成锚点加局部量。 */
    @Inject(method = "alignWithEntity(F)V", at = @At("RETURN"))
    private void farlands$computeCameraFrame(float partialTicks, CallbackInfo ci) {
        if (this.farlands$minecartFrame || !(this.entity instanceof ExactPosition holder)) {
            return;
        }
        double eye = (double) Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight);
        long anchorX = holder.farlands$anchor(ExactPosition.POS_X);
        long anchorY = holder.farlands$anchor(ExactPosition.POS_Y);
        long anchorZ = holder.farlands$anchor(ExactPosition.POS_Z);
        double localX = holder.farlands$lerpLocal(ExactPosition.POS_X, partialTicks) + this.farlands$moveX;
        double localY = holder.farlands$lerpLocal(ExactPosition.POS_Y, partialTicks) + eye + this.farlands$moveY;
        double localZ = holder.farlands$lerpLocal(ExactPosition.POS_Z, partialTicks) + this.farlands$moveZ;
        long stepX = (long) Math.floor(localX);
        long stepY = (long) Math.floor(localY);
        long stepZ = (long) Math.floor(localZ);
        this.farlands$cameraAnchorX = anchorX + stepX;
        this.farlands$cameraAnchorY = anchorY + stepY;
        this.farlands$cameraAnchorZ = anchorZ + stepZ;
        this.farlands$cameraLocalX = localX - (double) stepX;
        this.farlands$cameraLocalY = localY - (double) stepY;
        this.farlands$cameraLocalZ = localZ - (double) stepZ;
        this.farlands$cameraValid = true;
    }

    @Overwrite
    public FogType getFluidInCamera() {
        if (!this.initialized) {
            return FogType.NONE;
        }
        FluidState fluidState = this.level.getFluidState(this.blockPosition);
        if (fluidState.is(FluidTags.WATER)
                && this.position.y < (double) this.blockPosition.getY()
                        + (double) fluidState.getHeight(this.level, this.blockPosition)) {
            return FogType.WATER;
        }
        Camera.NearPlane nearPlane = this.getNearPlane(this.minecraft.options.fov().get().intValue());
        List<Vec3> points = Arrays.asList(
                nearPlane.getPointOnPlane(0.0F, 0.0F),
                nearPlane.getTopLeft(),
                nearPlane.getTopRight(),
                nearPlane.getBottomLeft(),
                nearPlane.getBottomRight());
        for (Vec3 point : points) {
            Vec3 offsetPos = this.position.add(point);
            BlockPos checkPos = BlockPos.containing(offsetPos);
            FluidState fluidStateAtPoint = this.level.getFluidState(checkPos);
            if (fluidStateAtPoint.is(FluidTags.LAVA)) {
                if (offsetPos.y <= (double) checkPos.getY()
                        + (double) fluidStateAtPoint.getHeight(this.level, checkPos)) {
                    return FogType.LAVA;
                }
            } else {
                BlockState blockState = this.level.getBlockState(checkPos);
                if (blockState.is(Blocks.POWDER_SNOW)) {
                    return FogType.POWDER_SNOW;
                }
            }
        }
        return FogType.NONE;
    }
}
