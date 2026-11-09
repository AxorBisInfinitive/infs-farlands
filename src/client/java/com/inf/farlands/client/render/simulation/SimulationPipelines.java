package com.inf.farlands.client.render.simulation;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import com.inf.farlands.InfsFarlands;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;

/**
 * 模拟层的三条渲染管线，逐项照抄原版地形的 {@code TERRAIN_SNIPPET}。
 *
 * <p>
 * 三层的差别只有两处：{@code ALPHA_CUTOUT} 宏与颜色目标。实体层不带这个宏，片元着色器里就没有 {@code discard}，
 * 驱动可以在着色之前按深度丢弃被挡住的片元，这是本层帧时间的主要来源。镂空层带 0.5，透明层带 0.01 并开混合，
 * 与原版同值。
 *
 * <p>
 * 与地形管线的其余差别集中在精度上。地形的段 UBO 用 {@code ivec3} 装绝对段原点，再在着色器里减相机块坐标；
 * 我们的层在 int 之外，绝对原点装不下，所以段偏移改成 {@code vec3} 的相机相对量，在 CPU 侧用 double 算好再落
 * float。其余 uniform、采样器、顶点格式、深度状态与地形一致。
 */
public final class SimulationPipelines {

    private static final Identifier VERTEX_SHADER = InfsFarlands.id("core/simulation_terrain");

    private static final Identifier FRAGMENT_SHADER = InfsFarlands.id("core/simulation_terrain");

    /** 实体层：不带 {@code discard}，early-Z 生效。 */
    public static final RenderPipeline SIMULATION_TERRAIN_SOLID = builder(
            InfsFarlands.id("pipeline/simulation_terrain_solid")).build();

    /** 镂空层：带 {@code ALPHA_CUTOUT} 0.5，只用于需要镂空的方块。 */
    public static final RenderPipeline SIMULATION_TERRAIN_CUTOUT = builder(
            InfsFarlands.id("pipeline/simulation_terrain_cutout"))
            .withShaderDefine("ALPHA_CUTOUT", 0.5F)
            .build();

    /** 透明层：带阈值 0.01 的 {@code discard} 与混合，与地形的透明层同值。 */
    public static final RenderPipeline SIMULATION_TERRAIN_TRANSLUCENT = builder(
            InfsFarlands.id("pipeline/simulation_terrain_translucent"))
            .withShaderDefine("ALPHA_CUTOUT", 0.01F)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

    private SimulationPipelines() {
    }

    /** 三层共用的底子，只差位置、宏与颜色目标。 */
    private static RenderPipeline.Builder builder(Identifier location) {
        return RenderPipeline.builder()
                .withLocation(location)
                .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("SimSection", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withVertexShader(VERTEX_SHADER)
                .withFragmentShader(FRAGMENT_SHADER);
    }

    /** 该层对应的管线。 */
    public static RenderPipeline of(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> SIMULATION_TERRAIN_SOLID;
            case CUTOUT -> SIMULATION_TERRAIN_CUTOUT;
            case TRANSLUCENT -> SIMULATION_TERRAIN_TRANSLUCENT;
        };
    }
}
