package com.inf.farlands.client.mixin.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;

import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
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
}
