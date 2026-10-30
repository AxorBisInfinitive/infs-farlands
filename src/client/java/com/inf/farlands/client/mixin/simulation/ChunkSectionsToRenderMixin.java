package com.inf.farlands.client.mixin.simulation;

import java.util.List;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.inf.farlands.client.render.simulation.SimulationPipelines;
import com.inf.farlands.client.render.simulation.SimulationRenderPass;

import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 借宿主的段绘制 pass 画模拟层。
 *
 * <p>
 * 拼接点取 {@code renderGroup} 里那一处 {@code renderPass.setPipeline}，不取紧随其后的
 * {@code drawMultipleIndexed}：后者被 {@code if (!draws.isEmpty())} 包住，原版没有段网格时一次都不执行，而
 * 我们的层恰好总在原版段全空的地方。{@code setPipeline} 在层的循环里无条件调用，因此必然发生。
 *
 * <p>
 * 借用的是整趟 pass：颜色与深度目标、默认 uniform、Sampler0 的方块图集与 Sampler2 的 lightmap 都已由宿主绑好，
 * 这里只改管线、画我们那一批、再把管线改回实参给的值。
 *
 * <p>
 * 索引缓冲用我们自己的，不由宿主那条共享顺序缓冲提供。宿主在 {@code renderGroup} 里先取共享缓冲的引用与类型、
 * 后使用；若我们在这中间请求扩容，宿主持有的就是已关闭的旧对象。自有之后不碰共享缓冲，draw 的默认索引参数
 * 传 null，每个 draw 自带缓冲与类型。
 *
 * <p>
 * 去重不比较管线：实参在调试线框模式下会是 {@code WIREFRAME}，比不了。改用一个位，在 {@code TRANSLUCENT} 那一支
 * 复位。依据是 {@code LevelRenderer.addMainPass} 里那两次 {@code renderGroup} 都无条件且顺序固定。
 */
@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin {

    /** 本帧是否已经画过模拟层。 */
    @Unique
    private static boolean farlands$drawn;

    @WrapOperation(method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void farlands$drawSimulation(RenderPass pass, RenderPipeline pipeline, Operation<Void> original,
            ChunkSectionLayerGroup group) {
        original.call(pass, pipeline);
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            farlands$drawn = false;
            return;
        }
        if (farlands$drawn) {
            return;
        }
        farlands$drawn = true;
        SimulationRenderPass.Batch batch = SimulationRenderPass.INSTANCE.batch();
        if (batch == null || batch.indexCount() == 0) {
            return;
        }
        pass.setPipeline(SimulationPipelines.SIMULATION_TERRAIN);
        pass.drawMultipleIndexed(batch.draws(), null, null,
                List.of("SimSection"), batch.infos());
        pass.setPipeline(pipeline);
    }
}
