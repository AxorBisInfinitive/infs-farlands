package com.inf.farlands.client.mixin.simulation;

import com.mojang.blaze3d.vertex.PoseStack;

import com.inf.farlands.client.render.ExactCameraOrigin;
import com.inf.farlands.client.render.simulation.SimulationGeometry;
import com.inf.farlands.client.render.simulation.SimulationRenderPass;
import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.client.simulation.SimulationMesher;
import com.inf.farlands.client.simulation.SimulationPick;
import com.inf.farlands.client.simulation.SimulationTimings;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 模拟方块层的选择框，另加它需要的部分刻采集。
 *
 * <p>
 * 方块本体与段几何已改由段级显存缓冲绘制，见 {@code ChunkSectionsToRenderMixin}；本类只剩选择框。
 *
 * <p>
 * 选择框不能借原版的数据流：{@code BlockOutlineRenderState} 的 {@code pos} 是三个 int，装不下 int 之外的段
 * 原点；而 {@code ShapeRenderer} 只吃 double 偏移，量化只发生在最后写顶点的 float 上，命中本身已经保证偏移落
 * 在 reach 加 16 之内。所以命中自己算，偏移自己给。
 *
 * <p>
 * 选择框在这里画：{@code extractBlockOutline} 的返回处按原版的命中输入在快照上重算一次视线命中，结果留到
 * 渲染期由 {@code renderBlockOutline} 的头部画出来。准星先命中真实方块时不画，那时原版自己会画。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    /** 本帧 {@code pick} 用的部分刻。由 {@code extractLevel} 的第三参带入，与 vanilla 的命中同源。 */
    @Unique
    private float farlands$partialTicks = 1.0F;

    /** 本帧算好的选择框命中，渲染期取用一次即清。 */
    @Unique
    private SimulationPick.Hit farlands$outline;

    /** 本帧的相机帧表示。{@code farlands$cameraValid} 为假时退回相机自己的 double 位置。 */
    @Unique
    private boolean farlands$cameraValid;

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

    /** 退路：把相机位置拆成锚点加局部量，算式与原先的两项展开相同。 */
    @Unique
    private static double farlands$cameraRelative(long world, double camera) {
        long anchor = Mth.lfloor(camera);
        return (double) (world - anchor) - (camera - (double) anchor);
    }

    /**
     * 全局重建时作废模拟层的已烘结果。
     *
     * <p>
     * 触发点：资源重载成功分支、{@code ambientOcclusion}、{@code cutoutLeaves}、{@code improvedTransparency}、
     * 群系混合半径、有效渲染距离变化，以及 {@code setLevel}。这些都改已烘入顶点或落在模型与图集上的东西，
     * 所以清模型部件缓存、释放全部段几何、把全部段放回待烘。
     *
     * <p>
     * {@code setLevel} 那一次要跳过：此时 {@code Simulation.setLayer} 还没跑，缓存与列都属于旧关卡，
     * 放回去也会被 {@code setLayer} 清掉。
     */
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void farlands$invalidateBakedGeometry(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        Simulation simulation = ((SimulationHolder) minecraft).simulation();
        if (simulation.level() != minecraft.level) {
            return;
        }
        SimulationMesher.clearParts();
        SimulationGeometry.instance().clear();
        simulation.requeueAllSections();
    }

    /**
     * 记下本帧的部分刻，并作废上一帧留下的命中。
     *
     * <p>
     * 第三参就是 {@code Minecraft.pick} 传给原版命中的那一个数，见 {@code GameRenderer.extract}，所以这里
     * 不必自己从 {@code DeltaTracker} 重算。
     */
    @Inject(method = "extractLevel(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V", at = @At("HEAD"))
    private void farlands$capturePartialTicks(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick,
            CallbackInfo ci) {
        this.farlands$partialTicks = deltaPartialTick;
        this.farlands$outline = null;
    }

    /**
     * 在抽取阶段末尾把模拟层的绘制批次准备好。
     *
     * <p>
     * 必须落在 pass 之外：{@code CommandEncoder.writeToBuffer} 在 pass 打开期间会抛
     * {@code Close the existing render pass before performing additional commands}，而渲染期那次拼接点就在
     * 宿主的段 pass 之内。抽取阶段没有开 pass，段 UBO 因此在这里写。
     */
    @Inject(method = "extractLevel(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V", at = @At("RETURN"))
    private void farlands$prepareSimulation(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick,
            CallbackInfo ci) {
        LevelRenderState state = Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState;
        ExactCameraOrigin origin = camera instanceof ExactCameraOrigin candidate && candidate.farlands$cameraValid()
                ? candidate
                : null;
        this.farlands$cameraValid = origin != null;
        if (origin != null) {
            this.farlands$cameraAnchorX = origin.farlands$cameraAnchor(ExactPosition.POS_X);
            this.farlands$cameraAnchorY = origin.farlands$cameraAnchor(ExactPosition.POS_Y);
            this.farlands$cameraAnchorZ = origin.farlands$cameraAnchor(ExactPosition.POS_Z);
            this.farlands$cameraLocalX = origin.farlands$cameraLocal(ExactPosition.POS_X);
            this.farlands$cameraLocalY = origin.farlands$cameraLocal(ExactPosition.POS_Y);
            this.farlands$cameraLocalZ = origin.farlands$cameraLocal(ExactPosition.POS_Z);
        }
        long prepareStart = SimulationTimings.begin();
        SimulationRenderPass.INSTANCE.prepare(
                state.chunkSectionsToRender == null ? null : state.chunkSectionsToRender.textureView(),
                state.cameraRenderState, origin);
        SimulationTimings.end(SimulationTimings.PREPARE, prepareStart);
    }

    /**
     * 按原版的命中输入在快照上重算视线命中。
     *
     * <p>
     * 输入与 {@code LocalPlayer.raycastHitResult} 的方块支同源：相机实体、同一部分刻、{@code getEyePosition}
     * 与 {@code getViewVector}、玩家的 {@code blockInteractionRange}。快照里没有实体，所以没有实体竞争那一路。
     *
     * <p>
     * 准星已经命中真实方块时不重算，让原版自己画。
     *
     * <p>
     * 锚点取 {@code RETURN} 不取 {@code TAIL}：该方法有三处 return，准星 miss 走的是前面那两处，{@code TAIL}
     * 只对应指令表里最后一个 return，也就是命中方块那一支之后。取 {@code TAIL} 会在唯一需要它的时候不执行。
     */
    @Inject(method = "extractBlockOutline(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V", at = @At("RETURN"))
    private void farlands$extractOutline(Camera camera, LevelRenderState state, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return;
        }
        Entity entity = camera.entity();
        if (entity == null || minecraft.player == null) {
            return;
        }
        Simulation simulation = ((SimulationHolder) minecraft).simulation();
        this.farlands$outline = SimulationPick.pick(
                simulation,
                entity,
                this.farlands$partialTicks,
                entity.getViewVector(this.farlands$partialTicks),
                minecraft.player.blockInteractionRange());
    }

    /**
     * 画选择框，只画一次。
     *
     * <p>
     * 这个方法被调用两次，靠 {@code onlyTranslucentBlocks} 分流；框走不透明那一批。取用即清空，所以同一帧
     * 不会画两遍，上一帧的残留也不会漏进来。
     *
     * <p>
     * 偏移是世界坐标减相机，与 vanilla 的 {@code renderHitOutline} 同形；形状的边坐标是 0 到 1，所以加完
     * 偏移正好落在块上。
     */
    @Inject(method = "renderBlockOutline(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;ZLnet/minecraft/client/renderer/state/level/LevelRenderState;)V", at = @At("HEAD"))
    private void farlands$renderOutline(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack,
            boolean onlyTranslucentBlocks, LevelRenderState state, CallbackInfo ci) {
        if (onlyTranslucentBlocks) {
            return;
        }
        SimulationPick.Hit hit = this.farlands$outline;
        this.farlands$outline = null;
        if (hit == null) {
            return;
        }
        Vec3 camera = state.cameraRenderState.pos;
        double x;
        double y;
        double z;
        if (this.farlands$cameraValid) {
            x = (double) (hit.originX() + hit.localX() - this.farlands$cameraAnchorX) - this.farlands$cameraLocalX;
            y = (double) (hit.originY() + hit.localY() - this.farlands$cameraAnchorY) - this.farlands$cameraLocalY;
            z = (double) (hit.originZ() + hit.localZ() - this.farlands$cameraAnchorZ) - this.farlands$cameraLocalZ;
        } else {
            x = farlands$cameraRelative(hit.originX() + hit.localX(), camera.x);
            y = farlands$cameraRelative(hit.originY() + hit.localY(), camera.y);
            z = farlands$cameraRelative(hit.originZ() + hit.localZ(), camera.z);
        }
        Minecraft minecraft = Minecraft.getInstance();
        ShapeRenderer.renderShape(poseStack, bufferSource.getBuffer(RenderTypes.lines()), hit.shape(), x, y, z,
                ARGB.black(102), minecraft.gameRenderer.getGameRenderState().windowRenderState.appropriateLineWidth);
    }

    /**
     * 一帧的命令全部提交之后推进一步段 UBO 的轮转。
     *
     * <p>
     * 锚点选这里而不是抽取阶段：{@code DynamicUniformStorage} 的 fence 建在轮转那一刻，只有建在本帧 draw 之后，
     * 三帧后复用同一条缓冲时等待它才是等在途的读。vanilla 自己也在 {@code RenderSystem.flipFrame} 里做同一件事，
     * 而 {@code flipFrame} 调的就是本方法。
     */
    @Inject(method = "endFrame", at = @At("RETURN"))
    private void farlands$endSimulationFrame(CallbackInfo ci) {
        SimulationRenderPass.INSTANCE.endFrame();
        SimulationTimings.frame();
    }
}
