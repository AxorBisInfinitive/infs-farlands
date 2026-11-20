package com.inf.farlands.client.mixin.debug.renderer;

import com.inf.farlands.client.render.ChunkBorderLines;
import com.inf.farlands.client.render.ExactCameraState;
import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.util.pos.ExactPosition;
import love.crysh.long2.ints.Int1024;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.ChunkBorderRenderer;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 区块边界线：段号取自 1280 位位置的精确下取整，端点以相机相对量交给 {@link ChunkBorderLines}。
 *
 * <p>
 * 原版这一层有两处进不了 1024 域。其一，中心段来自 {@code cameraEntity.blockPosition()}，而那是 int 字段，
 * {@code 2^31} 之上饱和到整数两端，网格会画在别处。其二，端点存成世界量级的 {@code Vec3} 再由 gizmo 管线减相机，
 * 两个操作数都已在各自的 ulp 网格上，16 格的相对关系在 {@code 2^57} 之上就没了。所以本类不经过 gizmo 管线。
 *
 * <p>
 * 相机帧取 {@code CameraRenderState} 上那一份，写入点是 {@code Camera.extractRenderState}，而 {@code GameRenderer}
 * 在 {@code extractLevel} 之前调了它，抽取阶段因此已可用。帧无效时本帧不画，不退回落差极大的 double 位置。
 */
@Mixin(ChunkBorderRenderer.class)
public class ChunkBorderRendererMixin {

    @Shadow
    private Minecraft minecraft;

    /** 段内立方体的 12 条棱。 */
    @Unique
    private static final int[][] farlands$edges = {
            { 0, 1 },
            { 0, 2 },
            { 0, 4 },
            { 1, 3 },
            { 1, 5 },
            { 2, 3 },
            { 2, 6 },
            { 3, 7 },
            { 4, 5 },
            { 4, 6 },
            { 5, 7 },
            { 6, 7 }
    };

    /** 段内立方体的 8 个顶点，单位是 16 格。 */
    @Unique
    private static final int[][] farlands$vertices = {
            { 0, 0, 0 },
            { 1, 0, 0 },
            { 0, 1, 0 },
            { 1, 1, 0 },
            { 0, 0, 1 },
            { 1, 0, 1 },
            { 0, 1, 1 },
            { 1, 1, 1 }
    };

    /** 中心加六个面邻，单位是 16 格。 */
    @Unique
    private static final int[][] farlands$neighbors = {
            { 0, 0, 0 },
            { -1, 0, 0 },
            { 1, 0, 0 },
            { 0, -1, 0 },
            { 0, 1, 0 },
            { 0, 0, -1 },
            { 0, 0, 1 }
    };

    @Unique
    private static final int farlands$RED = 0xFFFF0000;

    @Unique
    private static final int farlands$YELLOW = 0xFFFFFF00;

    @Unique
    private static final int farlands$BLUE = 0xFF0000FF;

    @Unique
    private static final int farlands$WHITE = 0xFFFFFFFF;

    @Inject(method = "emitGizmos", at = @At("HEAD"), cancellable = true)
    private void farlands$emitInt1024(double camX, double camY, double camZ, DebugValueAccess debugValues,
            Frustum frustum, float partialTicks, CallbackInfo ci) {
        ci.cancel();
        ChunkBorderLines.clear();
        Entity cameraEntity = this.minecraft.getCameraEntity();
        if (!(cameraEntity instanceof ExactPosition exact)) {
            return;
        }
        ExactCameraState camera = (ExactCameraState) Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || !camera.farlands$stateValid()) {
            return;
        }
        Int1024 sectionX = exact.farlands$cellCoord1024(ExactPosition.POS_X).shift(-4);
        Int1024 sectionY = exact.farlands$cellCoord1024(ExactPosition.POS_Y).shift(-4);
        Int1024 sectionZ = exact.farlands$cellCoord1024(ExactPosition.POS_Z).shift(-4);
        ChunkBorderLines.begin(farlands$relativeOrigin(sectionX, ExactPosition.POS_X, camera),
                farlands$relativeOrigin(sectionY, ExactPosition.POS_Y, camera),
                farlands$relativeOrigin(sectionZ, ExactPosition.POS_Z, camera));
        farlands$emitBlue();
        farlands$emitRed();
        farlands$emitYellow();
        farlands$emitWhite();
    }

    /**
     * 段原点到相机的位移，相机相对量。
     *
     * <p>
     * 锚点差是 {@link Int1024} 减法再转 double，差值只有几十格，与两个锚点各自的量级无关；局部偏移在这里一并减掉，
     * 所以结果精确。
     */
    @Unique
    private static double farlands$relativeOrigin(Int1024 sectionCoord, int axis, ExactCameraState camera) {
        return FrameMath.offset(sectionCoord.shift(4), 0.0, camera.farlands$stateAnchor(axis),
                camera.farlands$stateLocal(axis));
    }

    /** 蓝线：3 乘 3 乘 3 段的外框，跳过中心段。 */
    @Unique
    private static void farlands$emitBlue() {
        for (int dy = -1; dy <= 2; dy++) {
            for (int dz = -1; dz <= 2; dz++) {
                for (int sx = -1; sx <= 1; sx++) {
                    if (sx == 0 && dy >= 0 && dy <= 1 && dz >= 0 && dz <= 1) {
                        continue;
                    }
                    ChunkBorderLines.line(sx * 16, dy * 16, dz * 16, sx * 16 + 16, dy * 16, dz * 16,
                            farlands$BLUE, 2.0F);
                }
            }
        }
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                for (int sy = -1; sy <= 1; sy++) {
                    if (sy == 0 && dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) {
                        continue;
                    }
                    ChunkBorderLines.line(dx * 16, sy * 16, dz * 16, dx * 16, sy * 16 + 16, dz * 16,
                            farlands$BLUE, 2.0F);
                }
            }
        }
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int sz = -1; sz <= 1; sz++) {
                    if (sz == 0 && dx >= 0 && dx <= 1 && dy >= 0 && dy <= 1) {
                        continue;
                    }
                    ChunkBorderLines.line(dx * 16, dy * 16, sz * 16, dx * 16, dy * 16, sz * 16 + 16,
                            farlands$BLUE, 2.0F);
                }
            }
        }
    }

    /** 红线：中心段的六个面，格距 2。 */
    @Unique
    private static void farlands$emitRed() {
        for (int axis = 0; axis < 3; axis++) {
            ChunkBorderLines.faceGrid(0, 0, 0, axis, false, 2, farlands$RED, 2.5F);
            ChunkBorderLines.faceGrid(0, 0, 0, axis, true, 2, farlands$RED, 2.5F);
        }
    }

    /** 黄线：中心与六个面邻的面网格，中心格距 4，邻段格距 8。 */
    @Unique
    private static void farlands$emitYellow() {
        for (int[] offset : farlands$neighbors) {
            int step = offset[0] == 0 && offset[1] == 0 && offset[2] == 0 ? 4 : 8;
            for (int axis = 0; axis < 3; axis++) {
                ChunkBorderLines.faceGrid(offset[0] * 16, offset[1] * 16, offset[2] * 16, axis, false, step,
                        farlands$YELLOW, 1.5F);
                ChunkBorderLines.faceGrid(offset[0] * 16, offset[1] * 16, offset[2] * 16, axis, true, step,
                        farlands$YELLOW, 1.5F);
            }
        }
    }

    /** 白线：中心段的 1 乘 1 乘 1 立方。 */
    @Unique
    private static void farlands$emitWhite() {
        for (int[] edge : farlands$edges) {
            int[] from = farlands$vertices[edge[0]];
            int[] to = farlands$vertices[edge[1]];
            ChunkBorderLines.line(from[0] * 16, from[1] * 16, from[2] * 16, to[0] * 16, to[1] * 16, to[2] * 16,
                    farlands$WHITE, 3.0F);
        }
    }
}
