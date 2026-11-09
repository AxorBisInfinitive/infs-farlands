package com.inf.farlands.client.mixin.simulation;

import java.util.List;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.inf.farlands.client.render.simulation.SimulationPipelines;
import com.inf.farlands.client.render.simulation.SimulationRenderPass;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 借宿主的段绘制 pass 画模拟层，按层各画各的。
 *
 * <p>
 * 拼接点取 {@code renderGroup} 里那一处 {@code renderPass.setPipeline}，不取紧随其后的
 * {@code drawMultipleIndexed}：后者被 {@code if (!draws.isEmpty())} 包住，原版没有段网格时一次都不执行，而我们的层
 * 恰好总在原版段全空的地方。{@code setPipeline} 在层的循环里无条件调用，因此必然发生，而且实参就是宿主当前要画的那
 * 一层，正好用来选我们同层的那一批。
 *
 * <p>
 * 分层与宿主对齐：实体层在宿主的实体层那一趟里画，镂空层与透明层同理。透明层因此落在宿主的透明组 pass 里，混合状态
 * 由我们自己的管线给出，绘制顺序按段缓冲分组后的顺序。
 *
 * <p>
 * 借用的是整趟 pass：颜色与深度目标、默认 uniform、Sampler0 的方块图集与 Sampler2 的 lightmap 都已由宿主绑好，这里只
 * 改管线、画我们那一批、再把管线改回实参给的值。
 *
 * <p>
 * 每层在一个帧里只画一次。复位点放在透明层那一支：{@code LevelRenderer.addMainPass} 里两次 {@code renderGroup} 无条件
 * 且顺序固定，透明层是最后一层。
 *
 * <p>
 * 调试线框那一支认不出层，本层不画：宿主的 {@code WIREFRAME} 管线没有 {@code SimSection} 这个 uniform，绑不进去。
 */
@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin {

    /** 三条管线各自在本帧是否已经画过。 */
    @Unique
    private static boolean farlands$drawnSolid;

    @Unique
    private static boolean farlands$drawnCutout;

    @Unique
    private static boolean farlands$drawnTranslucent;

    @WrapOperation(method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void farlands$drawSimulation(RenderPass pass, RenderPipeline pipeline, Operation<Void> original,
            ChunkSectionLayerGroup group) {
        original.call(pass, pipeline);
        ChunkSectionLayer layer = farlands$layerOf(pipeline);
        if (layer == null) {
            if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                farlands$reset();
            }
            return;
        }
        try {
            farlands$paint(pass, pipeline, layer);
        } finally {
            // 复位点按层定，不按「有没有画」定：透明层没有几何时也要复位，否则下一帧三层都被跳过。
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                farlands$reset();
            }
        }
    }

    /** 画该层的那一批。没有批次或该层没有 draw 时直接返回。 */
    @Unique
    private static void farlands$paint(RenderPass pass, RenderPipeline hostPipeline, ChunkSectionLayer layer) {
        if (farlands$drawn(layer)) {
            return;
        }
        farlands$markDrawn(layer);
        SimulationRenderPass.Batch batch = SimulationRenderPass.INSTANCE.batch();
        if (batch == null) {
            return;
        }
        List<List<RenderPass.Draw<GpuBufferSlice[]>>> groups = batch.groups(layer);
        if (groups.isEmpty()) {
            return;
        }
        pass.setPipeline(SimulationPipelines.of(layer));
        for (List<RenderPass.Draw<GpuBufferSlice[]>> bufferGroup : groups) {
            pass.drawMultipleIndexed(bufferGroup, null, null, List.of("SimSection"), batch.infos());
        }
        pass.setPipeline(hostPipeline);
    }

    /** 复位三层的「本帧已画」标记。 */
    @Unique
    private static void farlands$reset() {
        farlands$drawnSolid = false;
        farlands$drawnCutout = false;
        farlands$drawnTranslucent = false;
    }

    /** 宿主实参对应的层，认不出返回 null。 */
    @Unique
    private static ChunkSectionLayer farlands$layerOf(RenderPipeline pipeline) {
        if (pipeline == RenderPipelines.SOLID_TERRAIN) {
            return ChunkSectionLayer.SOLID;
        }
        if (pipeline == RenderPipelines.CUTOUT_TERRAIN) {
            return ChunkSectionLayer.CUTOUT;
        }
        if (pipeline == RenderPipelines.TRANSLUCENT_TERRAIN) {
            return ChunkSectionLayer.TRANSLUCENT;
        }
        return null;
    }

    @Unique
    private static boolean farlands$drawn(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> farlands$drawnSolid;
            case CUTOUT -> farlands$drawnCutout;
            case TRANSLUCENT -> farlands$drawnTranslucent;
        };
    }

    @Unique
    private static void farlands$markDrawn(ChunkSectionLayer layer) {
        switch (layer) {
            case SOLID -> farlands$drawnSolid = true;
            case CUTOUT -> farlands$drawnCutout = true;
            case TRANSLUCENT -> farlands$drawnTranslucent = true;
        }
    }
}
