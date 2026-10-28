package com.inf.farlands.client.mixin.simulation;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import com.inf.farlands.client.simulation.FixedBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 纯客户端地在世界固定坐标渲染一个草方块，坐标 (2147483650, 2147483650, 2147483650)。
 *
 * <p>
 * 挂提交层而不是段编译：段的落点来自三个 int，段偏移是 {@code sectionCoord << 4}、块坐标是
 * {@code BlockPos}、区块坐标是 {@code SectionPos}，三者都在 int 上，目标坐标超出 int 上界 3 格，
 * 那一路必然回绕。这里的位置只由姿态携带，平移量是 {@code 目标 - 相机} 的 double 差，进矩阵时才落成
 * float，所以整条链上没有 int 上限。
 *
 * <p>
 * 每帧在 {@code submitEntities} 的头部提交一次方块模型，同一个 {@code pass.executes} 里的
 * {@code renderSolidFeatures} 随后消费它。几何在局部原点烘焙，位置全部来自这里压入的平移。
 *
 * <p>
 * 亮度写死 15 与 15。方块模型那条提交接口自带 lightCoords 参数，不走世界光照查询，也不经过
 * {@code MovingBlockRenderState} 那种自带空光源引擎的载体，所以不会画成黑的。
 *
 * <p>
 * 方块只活在客户端：不挡光、不可破坏、不参与遮挡与视锥，服务端不知道。碰撞也只在客户端算，形状由
 * {@code FixedBlock} 提供，装配在 {@code CollisionGetterMixin} 里。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    /** 方块光与天光都是 15。打包式是 {@code blockLight << 4 | skyLight << 20}。 */
    @Unique
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    /** 无染色层。草方块不吃染色，空数组让每个 quad 的 tintIndex 都落空。 */
    @Unique
    private static final int[] NO_TINT = new int[0];

    /**
     * 草方块的模型部件，首次用时建。
     *
     * <p>
     * 不用字段初始化器：Mixin 对 {@code @Unique} 字段的初始化器不保证执行。
     */
    @Unique
    private static List<BlockStateModelPart> farlands$modelParts;

    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void farlands$renderFixedBlock(PoseStack poseStack, LevelRenderState state,
            SubmitNodeCollector output, CallbackInfo ci) {
        Vec3 camera = state.cameraRenderState.pos;
        poseStack.pushPose();
        poseStack.translate(FixedBlock.POS - camera.x(),
                FixedBlock.POS - camera.y(),
                FixedBlock.POS - camera.z());
        output.submitBlockModel(poseStack, Sheets.cutoutBlockSheet(), farlands$modelParts(), NO_TINT,
                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    /** 草方块的部件表，取一次并缓存。 */
    @Unique
    private static List<BlockStateModelPart> farlands$modelParts() {
        List<BlockStateModelPart> parts = farlands$modelParts;
        if (parts == null) {
            BlockStateModelSet set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
            BlockStateModel model = set.get(Blocks.GRASS_BLOCK.defaultBlockState());
            List<BlockStateModelPart> built = new ArrayList<>();
            model.collectParts(RandomSource.create(0L), built);
            parts = List.copyOf(built);
            farlands$modelParts = parts;
        }
        return parts;
    }
}
