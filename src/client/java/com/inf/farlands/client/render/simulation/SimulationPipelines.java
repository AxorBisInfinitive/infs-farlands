package com.inf.farlands.client.render.simulation;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import com.inf.farlands.InfsFarlands;

import net.minecraft.resources.Identifier;

/**
 * 模拟层的渲染管线，逐项照抄地形的 {@code TERRAIN_SNIPPET}，只换两处：段 UBO 的名字，与那对着色器。
 *
 * <p>
 * 与地形管线的差别集中在精度上。地形的段 UBO 用 {@code ivec3} 装绝对段原点，再在着色器里减相机块坐标；
 * 我们的层在 int 之外，绝对原点装不下，所以段偏移改成 {@code vec3} 的相机相对量，在 CPU 侧用 double 算好再落
 * float。其余 uniform、采样器、顶点格式、深度状态、切边阈值与地形一致。
 */
public final class SimulationPipelines {

    private static final Identifier VERTEX_SHADER = InfsFarlands.id("core/simulation_terrain");

    private static final Identifier FRAGMENT_SHADER = InfsFarlands.id("core/simulation_terrain");

    public static final RenderPipeline SIMULATION_TERRAIN = RenderPipeline
            .builder()
            .withLocation(InfsFarlands.id("pipeline/simulation_terrain"))
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("SimSection", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withVertexShader(VERTEX_SHADER)
            .withFragmentShader(FRAGMENT_SHADER)
            .withShaderDefine("ALPHA_CUTOUT", 0.5F)
            .build();

    private SimulationPipelines() {
    }
}
