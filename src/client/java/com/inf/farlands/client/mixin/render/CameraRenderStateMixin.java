package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.render.ExactCameraState;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给相机渲染状态挂上帧表示。
 *
 * <p>
 * 字段由 {@code fix.y.CameraMixin} 在 {@code Camera.extractRenderState} 的返回处写入，来源是
 * {@code ExactCameraOrigin}。默认 {@code valid} 为假，GUI 实体预览那种当场新建的状态天然走退路。
 */
@Mixin(CameraRenderState.class)
public abstract class CameraRenderStateMixin implements ExactCameraState {

    @Unique
    private boolean farlands$stateValid;

    @Unique
    private long farlands$stateAnchorX;

    @Unique
    private long farlands$stateAnchorY;

    @Unique
    private long farlands$stateAnchorZ;

    @Unique
    private double farlands$stateLocalX;

    @Unique
    private double farlands$stateLocalY;

    @Unique
    private double farlands$stateLocalZ;

    @Override
    public boolean farlands$stateValid() {
        return this.farlands$stateValid;
    }

    @Override
    public long farlands$stateAnchor(int axis) {
        if (axis == ExactPosition.POS_X) {
            return this.farlands$stateAnchorX;
        }
        return axis == ExactPosition.POS_Y ? this.farlands$stateAnchorY : this.farlands$stateAnchorZ;
    }

    @Override
    public double farlands$stateLocal(int axis) {
        if (axis == ExactPosition.POS_X) {
            return this.farlands$stateLocalX;
        }
        return axis == ExactPosition.POS_Y ? this.farlands$stateLocalY : this.farlands$stateLocalZ;
    }

    @Override
    public void farlands$setState(long anchorX, long anchorY, long anchorZ,
            double localX, double localY, double localZ) {
        this.farlands$stateAnchorX = anchorX;
        this.farlands$stateAnchorY = anchorY;
        this.farlands$stateAnchorZ = anchorZ;
        this.farlands$stateLocalX = localX;
        this.farlands$stateLocalY = localY;
        this.farlands$stateLocalZ = localZ;
        this.farlands$stateValid = true;
    }

    @Override
    public void farlands$clearState() {
        this.farlands$stateValid = false;
    }
}
