package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.render.ExactRenderOrigin;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给实体渲染状态挂上帧表示。
 *
 * <p>
 * 基类注入，所有 {@code EntityRenderState} 子类的实例都实现该接口。{@code x/y/z} 不动，阴影扫描、矿车轨面
 * 与第三方渲染器读到的仍是世界量级的 double。
 */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements ExactRenderOrigin {

    @Unique
    private boolean farlands$originValid;

    @Unique
    private long farlands$originAnchorX;

    @Unique
    private long farlands$originAnchorY;

    @Unique
    private long farlands$originAnchorZ;

    @Unique
    private double farlands$originLocalX;

    @Unique
    private double farlands$originLocalY;

    @Unique
    private double farlands$originLocalZ;

    @Override
    public boolean farlands$originValid() {
        return this.farlands$originValid;
    }

    @Override
    public long farlands$originAnchor(int axis) {
        if (axis == ExactPosition.POS_X) {
            return this.farlands$originAnchorX;
        }
        return axis == ExactPosition.POS_Y ? this.farlands$originAnchorY : this.farlands$originAnchorZ;
    }

    @Override
    public double farlands$originLocal(int axis) {
        if (axis == ExactPosition.POS_X) {
            return this.farlands$originLocalX;
        }
        return axis == ExactPosition.POS_Y ? this.farlands$originLocalY : this.farlands$originLocalZ;
    }

    @Override
    public void farlands$setOrigin(long anchorX, long anchorY, long anchorZ,
            double localX, double localY, double localZ) {
        this.farlands$originAnchorX = anchorX;
        this.farlands$originAnchorY = anchorY;
        this.farlands$originAnchorZ = anchorZ;
        this.farlands$originLocalX = localX;
        this.farlands$originLocalY = localY;
        this.farlands$originLocalZ = localZ;
        this.farlands$originValid = true;
    }

    @Override
    public void farlands$clearOrigin() {
        this.farlands$originValid = false;
    }
}
