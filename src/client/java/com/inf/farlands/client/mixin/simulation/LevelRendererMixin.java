package com.inf.farlands.client.mixin.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;

import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.client.simulation.SimulationPick;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把模拟方块层的快照画出来，纯客户端。
 *
 * <p>
 * 挂提交层而不是段编译：段的落点来自三个 int，段偏移是 {@code sectionCoord << 4}、块坐标是
 * {@code BlockPos}、区块坐标是 {@code SectionPos}，三者都在 int 上，int 之外的坐标那一路必然回绕。
 * 这里的位置只由姿态携带，每段一次平移，平移量由 {@link Simulation#delta} 给出，靠 long 相减保住精度。
 *
 * <p>
 * 每帧在 {@code submitEntities} 的头部提交，同一个 {@code pass.executes} 里的 {@code renderSolidFeatures}
 * 随后消费它。几何在局部原点烘焙，位置全部来自这里压入的平移。
 *
 * <p>
 * 亮度写死 15 与 15。方块模型那条提交接口自带 lightCoords 参数，不走世界光照查询，所以不会画成黑的。
 *
 * <p>
 * 方块只活在客户端：不挡光、不可破坏、不参与遮挡与视锥，服务端不知道。碰撞也只在客户端算，见
 * {@code CollisionGetterMixin}。
 *
 * <p>
 * 选择框在这里一并画：{@code extractBlockOutline} 的尾部按原版的命中输入在快照上重算一次视线命中，
 * 结果留到渲染期由 {@code renderBlockOutline} 的头部画出来。位置只走 double 与 float，不经过
 * {@code BlockPos}，所以 int 之外也成立。准星先命中真实方块时不画，那时原版自己会画。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    /** 方块光与天光都是 15。打包式是 {@code blockLight << 4 | skyLight << 20}。 */
    @Unique
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    /** 无染色层。空数组让每个 quad 的 tintIndex 都落空。 */
    @Unique
    private static final int[] NO_TINT = new int[0];

    /** 方块状态到模型部件表，取一次缓存。渲染线程独占，不需要同步。 */
    @Unique
    private static final Map<BlockState, List<BlockStateModelPart>> PARTS = new HashMap<>();

    /** 本帧 {@code pick} 用的部分刻。由 {@code extractLevel} 的第三参带入，与 vanilla 的命中同源。 */
    @Unique
    private float farlands$partialTicks = 1.0F;

    /** 本帧算好的选择框命中，渲染期取用一次即清。 */
    @Unique
    private SimulationPick.Hit farlands$outline;

    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void farlands$renderSimulation(PoseStack poseStack, LevelRenderState state,
            SubmitNodeCollector output, CallbackInfo ci) {
        Simulation.Snapshot snapshot = ((SimulationHolder) Minecraft.getInstance()).simulation().snapshot();
        List<Simulation.SnapSection> sections = snapshot.sections();
        if (sections.isEmpty()) {
            return;
        }
        Vec3 camera = state.cameraRenderState.pos;
        RenderType renderType = Sheets.cutoutBlockSheet();
        for (Simulation.SnapSection section : sections) {
            poseStack.pushPose();
            poseStack.translate(Simulation.delta(section.originX(), camera.x()),
                    Simulation.delta(section.originY(), camera.y()),
                    Simulation.delta(section.originZ(), camera.z()));
            for (Simulation.SnapBlock block : section.blocks()) {
                poseStack.pushPose();
                poseStack.translate(block.localX(), block.localY(), block.localZ());
                output.submitBlockModel(poseStack, renderType, farlands$parts(block.block().state()), NO_TINT,
                        FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                poseStack.popPose();
            }
            poseStack.popPose();
        }
    }

    /** 该方块状态的模型部件表，首次取用时建。 */
    @Unique
    private static List<BlockStateModelPart> farlands$parts(BlockState state) {
        List<BlockStateModelPart> parts = PARTS.get(state);
        if (parts != null) {
            return parts;
        }
        BlockStateModelSet set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel model = set.get(state);
        List<BlockStateModelPart> built = new ArrayList<>();
        model.collectParts(RandomSource.create(0L), built);
        parts = List.copyOf(built);
        PARTS.put(state, parts);
        return parts;
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
        this.farlands$outline = SimulationPick.pick(
                ((SimulationHolder) minecraft).simulation().snapshot(),
                entity.getEyePosition(this.farlands$partialTicks),
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
        double x = Simulation.delta(hit.originX() + hit.localX(), camera.x());
        double y = Simulation.delta(hit.originY() + hit.localY(), camera.y());
        double z = Simulation.delta(hit.originZ() + hit.localZ(), camera.z());
        Minecraft minecraft = Minecraft.getInstance();
        ShapeRenderer.renderShape(poseStack, bufferSource.getBuffer(RenderTypes.lines()), hit.shape(), x, y, z,
                ARGB.black(102), minecraft.gameRenderer.getGameRenderState().windowRenderState.appropriateLineWidth);
    }
}
