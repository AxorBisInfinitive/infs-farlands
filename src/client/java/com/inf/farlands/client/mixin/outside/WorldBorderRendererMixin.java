package com.inf.farlands.client.mixin.outside;

import com.inf.farlands.FarlandsConfig;

import java.util.ArrayList;
import java.util.Collection;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.state.level.WorldBorderRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 六面体边界渲染。
 *
 * <p>
 * 顶点着色器算的是 {@code Position + ModelOffset}，再乘纯旋转的 ModelViewMat，所以世界坐标等于
 * {@code Position + ModelOffset + cameraPos}。vanilla 的 ModelOffset.y 是 {@code -cameraPos.y}，
 * 顶点 Y 是 ±depthFar，墙的世界 Y 跨度因此恒为 ±depthFar，与相机高度无关。
 *
 * <p>
 * 这里把 ModelOffset.y 换成 {@code clamp(camY, -limit + d, limit - d) - camY}，墙的世界跨度变成
 * {@code [clamp(camY - d, -limit, limit), clamp(camY + d, -limit, limit)]}，clamp 不生效时墙随相机
 * 同步。同时把顶点缓冲从 16 个扩到 24 个，多出的两个四边形是地板与天花板，顶点 Y 取墙的下边与上边，
 * 相机贴近 ±limit 时它们正好落在物理面上。
 *
 * <p>
 * 地板与天花板只在相机距该面小于 renderDistance 时提交，提交点在 render 的 draw 集合参数上。整帧
 * pass 能否打开由 extract 算出的 alpha 决定，所以那里也要把 Y 距离算进去，否则四面墙都很远时
 * pass 根本不跑，两个水平面没有机会提交。缓冲是跨帧缓存的，只在 needsRebuild 或边界盒变化时重建，
 * 相机高度只能走 uniform，不能烘进顶点。
 */
@Mixin(WorldBorderRenderer.class)
public abstract class WorldBorderRendererMixin {

    @Shadow
    @Final
    private GpuBuffer worldBorderBuffer;

    @Shadow
    @Final
    private RenderSystem.AutoStorageIndexBuffer indices;

    @Shadow
    private boolean needsRebuild;

    @Shadow
    private double lastMinX;

    @Shadow
    private double lastMinZ;

    @Shadow
    private double lastBorderMinX;

    @Shadow
    private double lastBorderMaxX;

    @Shadow
    private double lastBorderMinZ;

    @Shadow
    private double lastBorderMaxZ;

    @Unique
    private double farlands$cameraY;

    @Unique
    private double farlands$renderDistance;

    @Unique
    private float farlands$depthFar;

    /**
     * 上次重建顶点缓冲时用的竖向中心。
     *
     * <p>
     * 纹理的竖向相位是烘在顶点里的，而墙的竖向位置跟着相机走，两者每帧错开中心的位移，图案就会被
     * 相机拖着走。渲染时按这个差值把相位补回去。
     *
     * <p>
     * 存 double 不存 float：中心可以到 ±2147483631，float 在那里的步长是 128 格，存 float 会把每
     * 一格的位移整个抹平。
     */
    @Unique
    private double farlands$textureBakedCenterY;

    /** 取本帧的相机高度、视距与远平面，供两个参数注入器用。outside=true 时整个边界不画。 */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void farlands$captureCamera(WorldBorderRenderState state, Vec3 cameraPos, double renderDistance,
            double depthFar, CallbackInfo ci) {
        this.farlands$cameraY = cameraPos.y;
        this.farlands$renderDistance = renderDistance;
        this.farlands$depthFar = (float) depthFar;
        if (FarlandsConfig.outside) {
            ci.cancel();
        }
    }

    /**
     * 透明度与触发条件补上 Y 距离。
     *
     * <p>
     * vanilla 在 extract 里只按 XZ 距离决定画不画：站在巨型边界的中心时四个方向都远在视距之外，
     * alpha 归零，render 首句的 {@code alpha <= 0} 早退把整帧边界 pass 跳过，地板与天花板一并消失。
     * 这里把判据换成 {@code min(XZ 距离, Y 距离)}，贴近地板或天花板时即使四面墙都远也能画出对应的
     * 水平面。Y 距离不参与时 d 等于 vanilla 用的那个 XZ 距离，重算结果与 vanilla 逐位相同。
     *
     * <p>
     * tint 必须一并写：vanilla 只在它的 XZ 门通过时设 tint，而 WorldBorderRenderState.reset 只清
     * alpha，在 XZ 中心首次开门时 tint 仍是 0，只改 alpha 会得到一面黑墙。
     *
     * <p>
     * 距离取的是有符号的到墙距离，所以相机落在边界之外时本判据同样成立，与 vanilla 26.1.2 的第二个
     * 四条件组不同，后者在相机越出边界超过视距时抑制渲染。
     */
    @Inject(method = "extract", at = @At("RETURN"))
    private void farlands$alphaIncludingY(WorldBorder border, float deltaPartialTick, Vec3 cameraPos,
            double renderDistance, WorldBorderRenderState state, CallbackInfo ci) {
        if (FarlandsConfig.outside) {
            state.alpha = 0.0;
            return;
        }
        double limit = FarlandsConfig.borderAbsoluteMax - 16.0;
        double dXZ = Math.min(Math.min(cameraPos.x - state.minX, state.maxX - cameraPos.x),
                Math.min(cameraPos.z - state.minZ, state.maxZ - cameraPos.z));
        double dY = Math.min(cameraPos.y + limit, limit - cameraPos.y);
        double d = Math.min(dXZ, dY);
        if (d >= renderDistance) {
            state.alpha = 0.0;
            return;
        }
        state.alpha = Mth.clamp(Math.pow(1.0 - d / renderDistance, 4.0), 0.0, 1.0);
        state.tint = border.getStatus().getColor();
    }

    /** 24 个顶点：四面墙 16 个加地板、天花板各 4 个。 */
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/blaze3d/buffers/GpuBuffer;"), index = 2)
    private long farlands$growVertexBuffer(long size) {
        return size / 16L * 24L;
    }

    /** uniform 的 y 分量，即 ModelOffset.y。 */
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Vector3f;<init>(FFF)V"), index = 1)
    private float farlands$clampModelOffsetY(float y) {
        return (float) (this.farlands$centerY() - this.farlands$cameraY);
    }

    /**
     * 纹理矩阵的 v 分量。除了时间项，还要补上墙相对上次重建移动了多少。
     *
     * <p>
     * 纹理竖向每格 0.5，图案要与世界对齐就得让 {@code v + worldY / 2} 为常数。顶点的世界 Y 是
     * {@code center ± depthFar}，而 {@code v} 是重建时按当时的相机 Y 烘进去的，两者之间每帧差一个
     * 中心的位移，所以要减去位移的一半。
     *
     * <p>
     * 位移取 {@link #farlands$centerY()} 的差，不取相机 Y 的差：夹取生效时中心不动，相机照走，用相机
     * Y 会把图案继续拖着走。
     */
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;translation(FFF)Lorg/joml/Matrix4f;"), index = 1)
    private float farlands$verticalPhase(float v) {
        return v - 0.5F * (float) (this.farlands$centerY() - this.farlands$textureBakedCenterY);
    }

    /** 本帧墙的竖向中心，夹到边界范围内。相机位置的两种注入共用这一处算式。 */
    @Unique
    private double farlands$centerY() {
        double limit = FarlandsConfig.borderAbsoluteMax - 16.0;
        double d = this.farlands$depthFar;
        return Mth.clamp(this.farlands$cameraY, -limit + d, limit - d);
    }

    /**
     * 相机贴近地板或天花板时追加对应的一笔 draw。索引缓冲是共享的顺序缓冲，此时已有至少 6 个索引，
     * 再取一次不会替换 render 局部量持有的那个缓冲，也不会缩小它。
     */
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;Ljava/util/Collection;Ljava/lang/Object;)V"), index = 0)
    private Collection<RenderPass.Draw<WorldBorderRenderer>> farlands$addHorizontalPanels(
            Collection<RenderPass.Draw<WorldBorderRenderer>> draws) {
        double limit = FarlandsConfig.borderAbsoluteMax - 16.0;
        boolean floor = this.farlands$cameraY < -limit + this.farlands$renderDistance;
        boolean ceiling = this.farlands$cameraY > limit - this.farlands$renderDistance;
        if (!floor && !ceiling) {
            return draws;
        }
        GpuBuffer indexBuffer = this.indices.getBuffer(6);
        VertexFormat.IndexType indexType = this.indices.type();
        ArrayList<RenderPass.Draw<WorldBorderRenderer>> out = new ArrayList<>(draws);
        if (floor) {
            out.add(new RenderPass.Draw<>(0, this.worldBorderBuffer, indexBuffer, indexType, 0, 6, 16));
        }
        if (ceiling) {
            out.add(new RenderPass.Draw<>(0, this.worldBorderBuffer, indexBuffer, indexType, 0, 6, 20));
        }
        return out;
    }

    /**
     * 四面墙的顶点与 vanilla 逐字相同，其后追加地板与天花板。两个新面的 local XZ 与墙共用同一个裁剪
     * 矩形，Y 取墙的下边与上边，因此不需要额外的 uniform。
     *
     * <p>
     * 必须照旧回写 lastBorder 四个字段、lastMinX/lastMinZ 与 needsRebuild，否则
     * shouldRebuildWorldBorderBuffer 恒为真，每帧重建缓冲。
     */
    @Overwrite
    private void rebuildWorldBorderBuffer(WorldBorderRenderState state, double renderDistance, double cameraZ,
            double cameraX, float halfHeightY, float v1, float v0) {
        // 这一笔烘进去的竖向相位对应本帧的中心，记下来供渲染期补偿。v0 由本帧相机 Y 算出，同一帧。
        this.farlands$textureBakedCenterY = this.farlands$centerY();
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder
                .exactlySized(DefaultVertexFormat.POSITION_TEX.getVertexSize() * 4 * 6)) {
            double borderMinX = state.minX;
            double borderMaxX = state.maxX;
            double borderMinZ = state.minZ;
            double borderMaxZ = state.maxZ;
            double minZ = Math.max(Mth.floor(cameraZ - renderDistance), borderMinZ);
            double maxZ = Math.min(Mth.ceil(cameraZ + renderDistance), borderMaxZ);
            float u0z = (Mth.floor(minZ) & 1) * 0.5F;
            float u1z = (float) (maxZ - minZ) / 2.0F;
            double minX = Math.max(Mth.floor(cameraX - renderDistance), borderMinX);
            double maxX = Math.min(Mth.ceil(cameraX + renderDistance), borderMaxX);
            float u0x = (Mth.floor(minX) & 1) * 0.5F;
            float u1x = (float) (maxX - minX) / 2.0F;
            BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_TEX);
            bufferBuilder.addVertex(0.0F, -halfHeightY, (float) (borderMaxZ - minZ)).setUv(u0x, v1);
            bufferBuilder.addVertex((float) (maxX - minX), -halfHeightY, (float) (borderMaxZ - minZ))
                    .setUv(u1x + u0x, v1);
            bufferBuilder.addVertex((float) (maxX - minX), halfHeightY, (float) (borderMaxZ - minZ))
                    .setUv(u1x + u0x, v0);
            bufferBuilder.addVertex(0.0F, halfHeightY, (float) (borderMaxZ - minZ)).setUv(u0x, v0);
            bufferBuilder.addVertex(0.0F, -halfHeightY, 0.0F).setUv(u0z, v1);
            bufferBuilder.addVertex(0.0F, -halfHeightY, (float) (maxZ - minZ)).setUv(u1z + u0z, v1);
            bufferBuilder.addVertex(0.0F, halfHeightY, (float) (maxZ - minZ)).setUv(u1z + u0z, v0);
            bufferBuilder.addVertex(0.0F, halfHeightY, 0.0F).setUv(u0z, v0);
            bufferBuilder.addVertex((float) (maxX - minX), -halfHeightY, 0.0F).setUv(u0x, v1);
            bufferBuilder.addVertex(0.0F, -halfHeightY, 0.0F).setUv(u1x + u0x, v1);
            bufferBuilder.addVertex(0.0F, halfHeightY, 0.0F).setUv(u1x + u0x, v0);
            bufferBuilder.addVertex((float) (maxX - minX), halfHeightY, 0.0F).setUv(u0x, v0);
            bufferBuilder.addVertex((float) (borderMaxX - minX), -halfHeightY, (float) (maxZ - minZ)).setUv(u0z, v1);
            bufferBuilder.addVertex((float) (borderMaxX - minX), -halfHeightY, 0.0F).setUv(u1z + u0z, v1);
            bufferBuilder.addVertex((float) (borderMaxX - minX), halfHeightY, 0.0F).setUv(u1z + u0z, v0);
            bufferBuilder.addVertex((float) (borderMaxX - minX), halfHeightY, (float) (maxZ - minZ)).setUv(u0z, v0);
            bufferBuilder.addVertex(0.0F, -halfHeightY, 0.0F).setUv(u0x, u0z);
            bufferBuilder.addVertex((float) (maxX - minX), -halfHeightY, 0.0F).setUv(u1x + u0x, u0z);
            bufferBuilder.addVertex((float) (maxX - minX), -halfHeightY, (float) (maxZ - minZ))
                    .setUv(u1x + u0x, u1z + u0z);
            bufferBuilder.addVertex(0.0F, -halfHeightY, (float) (maxZ - minZ)).setUv(u0x, u1z + u0z);
            bufferBuilder.addVertex(0.0F, halfHeightY, 0.0F).setUv(u0x, u0z);
            bufferBuilder.addVertex((float) (maxX - minX), halfHeightY, 0.0F).setUv(u1x + u0x, u0z);
            bufferBuilder.addVertex((float) (maxX - minX), halfHeightY, (float) (maxZ - minZ))
                    .setUv(u1x + u0x, u1z + u0z);
            bufferBuilder.addVertex(0.0F, halfHeightY, (float) (maxZ - minZ)).setUv(u0x, u1z + u0z);

            try (MeshData meshData = bufferBuilder.buildOrThrow()) {
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.worldBorderBuffer.slice(),
                        meshData.vertexBuffer());
            }

            this.lastBorderMinX = borderMinX;
            this.lastBorderMaxX = borderMaxX;
            this.lastBorderMinZ = borderMinZ;
            this.lastBorderMaxZ = borderMaxZ;
            this.lastMinX = minX;
            this.lastMinZ = minZ;
            this.needsRebuild = false;
        }
    }
}
