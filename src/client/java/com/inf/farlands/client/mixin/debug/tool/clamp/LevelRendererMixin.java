package com.inf.farlands.client.mixin.debug.tool.clamp;

import com.inf.farlands.client.debug.tool.clamp.VirtualHit;
import com.inf.farlands.client.render.ExactCameraState;
import com.inf.farlands.client.render.FrameMath;
import com.inf.farlands.debug.tool.clamp.ClampMode;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.farlands.util.world.WorldBounds;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 钳制模式虚拟面的选择框。
 *
 * <p>
 * 可玩界内仍走 vanilla 那条：虚拟命中是 {@code BlockHitResult}，这里放行空气检查并给出整格形状。界外已经没有
 * {@code BlockPos} 装得下，命中保留原 MISS，框改由本类在抽取阶段现算、渲染阶段自绘：格号是 {@code Int1024}，
 * 顶点用相机相对量，与区块边界同一套做法。
 *
 * <p>
 * 精确认在 {@code extractLevel} 的头部算，早于同一次抽取里的 {@code extractBlockOutline}，因此模拟层的选择框能读到
 * 它并让位；两条选择框在界外互斥。可玩界内那一条不写精确通道，模拟层仍按 BLOCK 命中早退，行为与改动前一致。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    /** 抽取阶段定下本帧的精确命中，渲染阶段据此画框。 */
    @Inject(method = "extractLevel(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V", at = @At("HEAD"))
    private void farlands$prepareVirtualSurface(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick,
            CallbackInfo ci) {
        VirtualHit.clearExact();
        Minecraft minecraft = Minecraft.getInstance();
        Entity cameraEntity = minecraft.getCameraEntity();
        if (minecraft.player == null || !(cameraEntity instanceof ClampMode clamp)) {
            return;
        }
        if (!clamp.isClampEnabled() || cameraEntity.isShiftKeyDown()) {
            return;
        }
        double maxDistance = Math.max(minecraft.player.blockInteractionRange(),
                minecraft.player.entityInteractionRange());
        VirtualHit.Surface surface = VirtualHit.surface(cameraEntity, deltaPartialTick, maxDistance);
        if (surface == null || WorldBounds.inBlock(surface.x(), surface.y(), surface.z())) {
            // 够不着，或者格号在可玩界内，那一档由 vanilla 那条通道画。
            return;
        }
        VirtualHit.setExact(surface);
    }

    @Redirect(method = "extractBlockOutline", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
    private boolean virtualHitNotAir(BlockState state) {
        if (VirtualHit.isCurrent(Minecraft.getInstance().hitResult)) {
            return false;
        }
        return state.isAir();
    }

    @Redirect(method = "extractBlockOutline", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape virtualHitShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (VirtualHit.isPos(pos)) {
            return Shapes.create(new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
        }
        return state.getShape(level, pos, ctx);
    }

    /** 界外那一档的选择框：取抽取阶段算好的格号，用相机相对量画整格。 */
    @Inject(method = "renderBlockOutline(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;ZLnet/minecraft/client/renderer/state/level/LevelRenderState;)V", at = @At("HEAD"))
    private void farlands$renderVirtualOutline(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack,
            boolean onlyTranslucentBlocks, LevelRenderState state, CallbackInfo ci) {
        if (onlyTranslucentBlocks) {
            return;
        }
        VirtualHit.Surface surface = VirtualHit.exact();
        if (surface == null) {
            return;
        }
        if (!(state.cameraRenderState instanceof ExactCameraState camera) || !camera.farlands$stateValid()) {
            return;
        }
        double relativeX = FrameMath.offset(surface.x(), 0.0, camera.farlands$stateAnchor(ExactPosition.POS_X),
                camera.farlands$stateLocal(ExactPosition.POS_X));
        double relativeY = FrameMath.offset(surface.y(), 0.0, camera.farlands$stateAnchor(ExactPosition.POS_Y),
                camera.farlands$stateLocal(ExactPosition.POS_Y));
        double relativeZ = FrameMath.offset(surface.z(), 0.0, camera.farlands$stateAnchor(ExactPosition.POS_Z),
                camera.farlands$stateLocal(ExactPosition.POS_Z));
        Minecraft minecraft = Minecraft.getInstance();
        ShapeRenderer.renderShape(poseStack, bufferSource.getBuffer(RenderTypes.lines()), Shapes.block(), relativeX,
                relativeY, relativeZ, ARGB.black(102),
                minecraft.gameRenderer.getGameRenderState().windowRenderState.appropriateLineWidth);
    }
}
