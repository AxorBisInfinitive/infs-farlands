package com.inf.farlands.client.simulation;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexFormat;

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

import org.lwjgl.system.MemoryUtil;

/**
 * 把一段模拟方块烘成段内局部的顶点数据，绑定一次、之后只上传。
 *
 * <p>
 * 跑在 {@code farlands-mesh} 的工作线程上，输入是 {@link BakeSnapshot}，输出是一份堆上的顶点缓冲。本类不碰显存、
 * 不碰段表、不碰 {@code Minecraft} 单例：模型集与方块色表都由快照带入。
 *
 * <p>
 * 顶点格式取 {@code DefaultVertexFormat.BLOCK}，与地形同一套，位置是段内 {@code 0} 到 {@code 16}。世界位置不进顶点，
 * 全部靠段 UBO 里的相机相对偏移，所以 int 之外的层不会在顶点这层丢精度。
 *
 * <p>
 * 面剔除跨段，判据是 {@code Block.shouldRenderFace}：邻块经 {@link SectionGetter} 按段内局部坐标取，越出本段时查
 * 快照里的邻段，缺段按空气，于是段边界与段内走同一条判据。
 *
 * <p>
 * 着色走原版的两条路径：门成立时用 {@link BlockModelLighter#prepareQuadAmbientOcclusion}，否则用
 * {@link BlockModelLighter#prepareQuadFlat}。光与明暗的缓存是线程局部的，必须与一段的烘制同起同落：键是段内局部
 * 坐标的打包值，跨段复用会让光值串段。
 *
 * <p>
 * 顶点按四边形四元一组顺序写出，与四边形的顺序索引配套，即每 4 个顶点接 6 个索引 {@code 0 1 2 2 3 0}。
 */
public final class SimulationMesher {

    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * 方块状态到模型部件表。条目带上它取自哪一份模型集：资源重载会换新的模型集，同一状态在那之前的部件表不再有效。
     */
    private static final Map<BlockState, CachedParts> PARTS = new ConcurrentHashMap<>();

    private record CachedParts(BlockStateModelSet set, List<BlockStateModelPart> parts) {
    }

    private SimulationMesher() {
    }

    /** 一段的几何：顶点缓冲与顶点数。没有可画的四边形时为 null。 */
    public record Mesh(ByteBuffer vertices, int vertexCount) {
    }

    /** 烘一段。没有可画的四边形时返回 null。 */
    public static Mesh build(BakeSnapshot snapshot) {
        int occupied = snapshot.occupiedCount();
        int[] occupiedIndexes = snapshot.occupied();
        SimulationSection section = snapshot.section();
        int quads = 0;
        SectionGetter getter = new SectionGetter(snapshot);
        BlockModelLighter lighter = new BlockModelLighter();
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        boolean ambientOcclusion = snapshot.ambientOcclusion();
        // 光与明暗的查询缓存是线程局部的，键是位置的打包值。本层的孔位是段内局部坐标，两个不同段会给出相同键，
        // 所以它必须与一段的烘制同起同落，绝不能跨段复用。
        BlockModelLighter.enableCaching();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(8192)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            QuadInstance instance = new QuadInstance();
            instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
            for (int i = 0; i < occupied; i++) {
                int index = occupiedIndexes[i];
                int x = (index >> 8) & 15;
                int y = (index >> 4) & 15;
                int z = index & 15;
                SimulationBlock block = section.blockAt(x, y, z);
                if (block == null) {
                    continue;
                }
                BlockState state = block.state();
                List<BlockStateModelPart> parts = farlands$parts(snapshot.models(), state);
                getter.at(x, y, z, state);
                localPos.set(x, y, z);
                boolean smooth = ambientOcclusion && state.getLightEmission() == 0
                        && !parts.isEmpty() && parts.getFirst().useAmbientOcclusion();
                for (Direction direction : DIRECTIONS) {
                    if (farlands$hidden(getter, state, localPos, neighbor, direction)) {
                        continue;
                    }
                    for (BlockStateModelPart part : parts) {
                        for (BakedQuad quad : part.getQuads(direction)) {
                            farlands$prepare(lighter, getter, localPos, neighbor, direction, quad, instance, smooth);
                            builder.putBlockBakedQuad(x, y, z, quad, instance);
                            quads++;
                        }
                    }
                }
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(null)) {
                        farlands$prepare(lighter, getter, localPos, neighbor, null, quad, instance, smooth);
                        builder.putBlockBakedQuad(x, y, z, quad, instance);
                        quads++;
                    }
                }
            }
            if (quads == 0) {
                return null;
            }
            MeshData mesh = builder.buildOrThrow();
            try {
                ByteBuffer source = mesh.vertexBuffer();
                // 必须是直接缓冲：上传走 glBufferStorage，它吃的是内存地址，堆缓冲没有地址，传上去是无效内容。
                ByteBuffer vertices = MemoryUtil.memAlloc(source.remaining());
                vertices.put(source.duplicate());
                vertices.flip();
                return new Mesh(vertices, quads * 4);
            } finally {
                mesh.close();
            }
        } finally {
            BlockModelLighter.clearCache();
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
            BlockPos.MutableBlockPos localPos, BlockPos.MutableBlockPos neighbor, Direction direction, BakedQuad quad,
            QuadInstance instance, boolean smooth) {
        BlockState state = getter.state();
        if (smooth) {
            lighter.prepareQuadAmbientOcclusion(getter, state, localPos, quad, instance);
        } else {
            int light = direction == null ? BlockModelLighter.CHECK_LIGHT
                    : lighter.getLightCoords(state, getter, neighbor.setWithOffset(localPos, direction));
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
     * 里空 chunk 也是空气，面照样可见。邻块位置用调用方传入的可变载体，不每个面新分配一个位置对象。
     */
    private static boolean farlands$hidden(SectionGetter getter, BlockState state, BlockPos localPos,
            BlockPos.MutableBlockPos neighbor, Direction direction) {
        BlockState neighborState = getter.getBlockState(neighbor.setWithOffset(localPos, direction));
        return !Block.shouldRenderFace(state, neighborState, direction);
    }

    /** 清空模型部件缓存。资源重载与模型相关选项变化后由主线程调。 */
    public static void clearParts() {
        PARTS.clear();
    }

    /**
     * 释放一份网格的顶点缓冲。
     *
     * <p>
     * 上传成功时由 {@code SimulationGeometry.upload} 释放；结果因代际或版本不符被丢弃时由取走它的一方释放。
     * 缓冲是直接内存，漏掉就是显存之外的一处泄漏。
     */
    static void release(Mesh mesh) {
        if (mesh != null) {
            MemoryUtil.memFree(mesh.vertices());
        }
    }

    /** 该方块状态的模型部件表。条目带模型集身份，换了模型集就重建。 */
    private static List<BlockStateModelPart> farlands$parts(BlockStateModelSet models, BlockState state) {
        CachedParts cached = PARTS.get(state);
        if (cached != null && cached.set() == models) {
            return cached.parts();
        }
        BlockStateModel model = models.get(state);
        java.util.ArrayList<BlockStateModelPart> built = new java.util.ArrayList<>();
        model.collectParts(RandomSource.create(0L), built);
        List<BlockStateModelPart> parts = List.copyOf(built);
        PARTS.put(state, new CachedParts(models, parts));
        return parts;
    }
}
