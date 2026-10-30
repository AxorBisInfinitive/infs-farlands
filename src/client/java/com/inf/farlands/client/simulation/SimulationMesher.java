package com.inf.farlands.client.simulation;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把一段模拟方块烘成段内局部的顶点数据，绑定一次、之后只上传。
 *
 * <p>
 * 顶点格式取 {@code DefaultVertexFormat.BLOCK}，与地形同一套，位置是段内 {@code 0} 到 {@code 16}。世界位置
 * 不进顶点，全部靠段 UBO 里的相机相对偏移，所以 int 之外的层不会在顶点这层丢精度。
 *
 * <p>
 * 面剔除跨段，判据是 {@code Block.shouldRenderFace}：邻块经 {@link SectionGetter} 按段内局部坐标取，越出本段时
 * 查邻段，缺段按空气，于是段边界与段内走同一条判据，与 vanilla 的 3x3x3 段区域同口径。邻段的存在性因此是烘制
 * 输入，绑定时由 {@code Simulation} 把邻列放回待烘。
 *
 * <p>
 * 着色走原版的两条路径：门成立时用 {@link BlockModelLighter#prepareQuadAmbientOcclusion}，否则用
 * {@link BlockModelLighter#prepareQuadFlat}。取数与剔除共用同一个 {@link SectionGetter}，它把位置当作段内局部
 * 坐标，坐标与群系按 long 与段内 quart 取。tint 按原版口径相乘，方向明暗取该维度那份。
 *
 * <p>
 * 顶点按四边形四元一组顺序写出，与四边形的顺序索引配套，即每 4 个顶点接 6 个索引 {@code 0 1 2 2 3 0}。
 */
public final class SimulationMesher {

    private static final Direction[] DIRECTIONS = Direction.values();

    /** 方块状态到模型部件表，取一次缓存。渲染线程独占。 */
    private static final Map<BlockState, List<BlockStateModelPart>> PARTS = new HashMap<>();

    private SimulationMesher() {
    }

    /** 一段的几何：顶点字节与顶点数。空段返回 null。包围盒由段容器的格并集给出。 */
    public record Mesh(byte[] vertices, int vertexCount) {
    }

    /** 烘一段。空段返回 null。 */
    public static Mesh build(SimulationSection section, Simulation simulation) {
        int occupied = section.occupiedCount();
        int quads = 0;
        SectionGetter getter = new SectionGetter(section, simulation);
        BlockModelLighter lighter = new BlockModelLighter();
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        boolean ambientOcclusion = Minecraft.getInstance().options.ambientOcclusion().get();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(8192)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            PoseStack pose = new PoseStack();
            QuadInstance instance = new QuadInstance();
            instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
            for (int i = 0; i < occupied; i++) {
                int index = section.occupiedIndex(i);
                int x = (index >> 8) & 15;
                int y = (index >> 4) & 15;
                int z = index & 15;
                SimulationBlock block = section.blockAt(x, y, z);
                if (block == null) {
                    continue;
                }
                pose.pushPose();
                pose.translate(x, y, z);
                PoseStack.Pose last = pose.last();
                BlockState state = block.state();
                List<BlockStateModelPart> parts = farlands$parts(state);
                getter.at(x, y, z, state);
                localPos.set(x, y, z);
                boolean smooth = ambientOcclusion && state.getLightEmission() == 0
                        && !parts.isEmpty() && parts.getFirst().useAmbientOcclusion();
                for (Direction direction : DIRECTIONS) {
                    if (farlands$hidden(getter, state, localPos, direction)) {
                        continue;
                    }
                    for (BlockStateModelPart part : parts) {
                        for (BakedQuad quad : part.getQuads(direction)) {
                            farlands$prepare(lighter, getter, localPos, direction, quad, instance, smooth);
                            builder.putBakedQuad(last, quad, instance);
                            quads++;
                        }
                    }
                }
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(null)) {
                        farlands$prepare(lighter, getter, localPos, null, quad, instance, smooth);
                        builder.putBakedQuad(last, quad, instance);
                        quads++;
                    }
                }
                pose.popPose();
            }
            if (quads == 0) {
                return null;
            }
            MeshData mesh = builder.buildOrThrow();
            try {
                ByteBuffer source = mesh.vertexBuffer();
                byte[] vertices = new byte[source.remaining()];
                source.duplicate().get(vertices);
                return new Mesh(vertices, quads * 4);
            } finally {
                mesh.close();
            }
        }
    }

    /**
     * 一个 quad 的着色：先按原版的门选平滑或平坦，再把 tint 乘上去。
     *
     * <p>
     * tint 用 {@code multiplyColor} 而不是覆盖：vanilla 的顺序是先铺方向明暗的灰、再乘 tint，覆盖会把明暗
     * 抹平。无 tint 层时乘 -1，等于不乘。
     */
    private static void farlands$prepare(BlockModelLighter lighter, SectionGetter getter,
            BlockPos.MutableBlockPos localPos, Direction direction, BakedQuad quad, QuadInstance instance,
            boolean smooth) {
        BlockState state = getter.state();
        if (smooth) {
            lighter.prepareQuadAmbientOcclusion(getter, state, localPos, quad, instance);
        } else {
            int light = direction == null ? BlockModelLighter.CHECK_LIGHT
                    : lighter.getLightCoords(state, getter, localPos.relative(direction));
            lighter.prepareQuadFlat(getter, state, localPos, light, quad, instance);
        }
        int tintIndex = quad.materialInfo().tintIndex();
        if (tintIndex >= 0) {
            instance.multiplyColor(getter.tintColor(tintIndex));
        }
    }

    /**
     * 该方向上是否被邻块挡住。判据与 vanilla 相同：{@code Block.shouldRenderFace} 比较两方遮挡形状在该面上的差，
     * 有露出的部分就保留，所以半砖只挡住它盖住的那部分。
     *
     * <p>
     * 邻块经 getter 按段内局部坐标取，跨段查邻段，缺段给空气，于是段边界与段内同判据；vanilla 的 3x3x3 段区域
     * 里空 chunk 也是空气，面照样可见。
     */
    private static boolean farlands$hidden(SectionGetter getter, BlockState state, BlockPos localPos,
            Direction direction) {
        BlockState neighbor = getter.getBlockState(localPos.relative(direction));
        return !Block.shouldRenderFace(state, neighbor, direction);
    }

    /** 清空模型部件缓存。资源重载与模型相关选项变化后调，下一次烘制重新取模型集。 */
    public static void clearParts() {
        PARTS.clear();
    }

    /** 该方块状态的模型部件表，首次取用时建。 */
    private static List<BlockStateModelPart> farlands$parts(BlockState state) {
        List<BlockStateModelPart> parts = PARTS.get(state);
        if (parts != null) {
            return parts;
        }
        BlockStateModelSet set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel model = set.get(state);
        java.util.ArrayList<BlockStateModelPart> built = new java.util.ArrayList<>();
        model.collectParts(RandomSource.create(0L), built);
        parts = List.copyOf(built);
        PARTS.put(state, parts);
        return parts;
    }
}
