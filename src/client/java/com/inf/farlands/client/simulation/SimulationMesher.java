package com.inf.farlands.client.simulation;

import java.nio.ByteBuffer;
import java.util.EnumMap;
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
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.lwjgl.system.MemoryUtil;

/**
 * 把一段模拟方块烘成段内局部的顶点数据，按渲染层分成最多三份。
 *
 * <p>
 * 跑在 {@code farlands-mesh} 的工作线程上，输入是 {@link BakeSnapshot}，输出是直接内存里的顶点缓冲。本类不碰显存、
 * 不碰段表、不碰 {@code Minecraft} 单例：模型集与方块色表都由快照带入。
 *
 * <p>
 * 分层依据与 {@code SectionCompiler} 一致：四边形自带 {@code materialInfo().layer()}，唯一例外是
 * {@code ModelBlockRenderer.forceOpaque} 为真时整块并入实体层。分层的意义在片元侧：实体层不带 {@code discard}，
 * 深度测试可以在着色之前丢掉被挡住的片元。
 *
 * <p>
 * 透明层多做一步排序：{@code MeshData.sortQuads} 按到相机的距离排四边形，再生成一份自有索引，否则混合顺序会随
 * 段内遍历顺序错乱。排序用的相机位置由快照带进来，因此只反映提交那一刻，与地形在编译期排一次同源。
 *
 * <p>
 * 顶点格式取 {@code DefaultVertexFormat.BLOCK}，位置是段内 {@code 0} 到 {@code 16}。世界位置不进顶点，全部靠段
 * UBO 里的相机相对偏移，所以 int 之外的层不会在顶点这层丢精度。
 *
 * <p>
 * 面剔除跨段，判据是 {@code Block.shouldRenderFace}：邻块经 {@link SectionGetter} 按段内局部坐标取，越出本段时查
 * 快照里的邻段，缺段按空气，于是段边界与段内走同一条判据。
 *
 * <p>
 * 着色走原版的两条路径：门成立时用 {@link BlockModelLighter#prepareQuadAmbientOcclusion}，否则用
 * {@link BlockModelLighter#prepareQuadFlat}。光与明暗的查询缓存是线程局部的，必须与一段的烘制同起同落。
 */
public final class SimulationMesher {

    private static final Direction[] DIRECTIONS = Direction.values();

    /** 三层，顺序即 {@link ChunkSectionLayer} 的枚举序。 */
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

    /**
     * 方块状态到模型部件表。条目带上它取自哪一份模型集：资源重载会换新的模型集，同一状态在那之前的部件表不再有效。
     */
    private static final Map<BlockState, CachedParts> PARTS = new ConcurrentHashMap<>();

    private record CachedParts(BlockStateModelSet set, List<BlockStateModelPart> parts) {
    }

    /** 一层正在攒的缓冲。 */
    private static final class LayerBuffer {

        private final ByteBufferBuilder bytes;

        private final BufferBuilder builder;

        private int quads;

        LayerBuffer() {
            this.bytes = new ByteBufferBuilder(8192);
            this.builder = new BufferBuilder(this.bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        }

        void close() {
            this.bytes.close();
        }
    }

    private SimulationMesher() {
    }

    /**
     * 一份层的几何：顶点缓冲、顶点数与可选的索引。
     *
     * <p>
     * {@code indices} 为 null 表示走共享顺序索引，绘制时由 {@code baseVertex} 平移；非 null 表示这一层的顺序排过，
     * 必须用它自己的索引，{@code indexType} 随之确定。
     */
    public record Mesh(ByteBuffer vertices, int vertexCount, ByteBuffer indices, VertexFormat.IndexType indexType) {

        /** 本条 draw 的索引数。 */
        public int indexCount() {
            return this.indices != null ? this.indices.remaining() / this.indexType.bytes : this.vertexCount / 4 * 6;
        }
    }

    /** 一段烘制的结果：三层各自可为 null，掩码始终有效。 */
    public record Build(Mesh solid, Mesh cutout, Mesh translucent, long visibility) {
    }

    /** 烘一段。三层都可能是 null。 */
    public static Build build(BakeSnapshot snapshot) {
        SimulationSection.Content content = snapshot.content();
        int occupied = content.occupiedCount();
        int[] occupiedIndexes = content.occupied();
        SectionGetter getter = new SectionGetter(snapshot);
        BlockModelLighter lighter = new BlockModelLighter();
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        QuadInstance instance = new QuadInstance();
        instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        boolean ambientOcclusion = snapshot.ambientOcclusion();
        boolean cutoutLeaves = snapshot.cutoutLeaves();
        // 光与明暗的查询缓存是线程局部的，键是位置的打包值。本层的孔位是段内局部坐标，两个不同段会给出相同键，
        // 所以它必须与一段的烘制同起同落，绝不能跨段复用。
        BlockModelLighter.enableCaching();
        Map<ChunkSectionLayer, LayerBuffer> layers = new EnumMap<>(ChunkSectionLayer.class);
        // 掩码的输入就是循环里已经在手的 BlockState：遮挡体判据照抄原版 SectionCompiler，只看 isSolidRender。
        VisGraph visGraph = new VisGraph();
        try {
            for (int i = 0; i < occupied; i++) {
                int index = occupiedIndexes[i];
                int x = (index >> 8) & 15;
                int y = (index >> 4) & 15;
                int z = index & 15;
                SimulationBlock block = content.blockAt(index);
                if (block == null) {
                    continue;
                }
                BlockState state = block.state();
                List<BlockStateModelPart> parts = farlands$parts(snapshot.models(), state);
                getter.at(x, y, z, state);
                localPos.set(x, y, z);
                if (state.isSolidRender()) {
                    visGraph.setOpaque(localPos);
                }
                boolean smooth = ambientOcclusion && state.getLightEmission() == 0
                        && !parts.isEmpty() && parts.getFirst().useAmbientOcclusion();
                boolean forceOpaque = ModelBlockRenderer.forceOpaque(cutoutLeaves, state);
                for (Direction direction : DIRECTIONS) {
                    if (farlands$hidden(getter, state, localPos, neighbor, direction)) {
                        continue;
                    }
                    for (BlockStateModelPart part : parts) {
                        for (BakedQuad quad : part.getQuads(direction)) {
                            farlands$prepare(lighter, getter, localPos, neighbor, direction, quad, instance, smooth);
                            LayerBuffer target = farlands$layer(layers, farlands$layerOf(quad, forceOpaque));
                            target.builder.putBlockBakedQuad(x, y, z, quad, instance);
                            target.quads++;
                        }
                    }
                }
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(null)) {
                        farlands$prepare(lighter, getter, localPos, neighbor, null, quad, instance, smooth);
                        LayerBuffer target = farlands$layer(layers, farlands$layerOf(quad, forceOpaque));
                        target.builder.putBlockBakedQuad(x, y, z, quad, instance);
                        target.quads++;
                    }
                }
            }
            long visibility = farlands$visibility(visGraph.resolve());
            return new Build(farlands$finish(snapshot, ChunkSectionLayer.SOLID, layers.get(ChunkSectionLayer.SOLID)),
                    farlands$finish(snapshot, ChunkSectionLayer.CUTOUT, layers.get(ChunkSectionLayer.CUTOUT)),
                    farlands$finish(snapshot, ChunkSectionLayer.TRANSLUCENT,
                            layers.get(ChunkSectionLayer.TRANSLUCENT)),
                    visibility);
        } finally {
            for (LayerBuffer buffer : layers.values()) {
                buffer.close();
            }
            BlockModelLighter.clearCache();
        }
    }

    /** 四边形该进哪一层。实体覆盖优先，其余按它材质上带的层。 */
    private static ChunkSectionLayer farlands$layerOf(BakedQuad quad, boolean forceOpaque) {        if (forceOpaque) {
            return ChunkSectionLayer.SOLID;
        }
        ChunkSectionLayer layer = quad.materialInfo().layer();
        return layer == null ? ChunkSectionLayer.SOLID : layer;
    }

    private static LayerBuffer farlands$layer(Map<ChunkSectionLayer, LayerBuffer> layers, ChunkSectionLayer layer) {
        return layers.computeIfAbsent(layer, k -> new LayerBuffer());
    }

    /**
     * 收一层：建网格，透明层再排一次序并生成自有索引。
     *
     * <p>
     * 顶点与索引都拷进直接内存再交出去：上传入口吃的是内存地址，堆缓冲没有地址。切片在本方法返回前失效，所以必须拷
     * 贝。
     */
    private static Mesh farlands$finish(BakeSnapshot snapshot, ChunkSectionLayer layer, LayerBuffer buffer) {
        if (buffer == null || buffer.quads == 0) {
            return null;
        }
        MeshData mesh = buffer.builder.build();
        if (mesh == null) {
            return null;
        }
        try {
            ByteBuffer source = mesh.vertexBuffer();
            int vertexCount = buffer.quads * 4;
            ByteBuffer vertices = MemoryUtil.memAlloc(source.remaining());
            vertices.put(source.duplicate());
            vertices.flip();
            ByteBuffer indices = layer == ChunkSectionLayer.TRANSLUCENT
                    ? farlands$sortedIndices(snapshot, mesh, vertexCount)
                    : null;
            if (indices == null) {
                return new Mesh(vertices, vertexCount, null, null);
            }
            return new Mesh(vertices, vertexCount, indices, VertexFormat.IndexType.least(vertexCount));
        } finally {
            mesh.close();
        }
    }

    /** 按到相机的距离排四边形并生成自有索引。排不出来时返回 null，调用方退回共享索引。 */
    private static ByteBuffer farlands$sortedIndices(BakeSnapshot snapshot, MeshData mesh, int vertexCount) {
        try (ByteBufferBuilder target = new ByteBufferBuilder(2048)) {
            MeshData.SortState state = mesh.sortQuads(target, snapshot.sorting());
            if (state == null) {
                return null;
            }
            ByteBufferBuilder.Result result = state.buildSortedIndexBuffer(target, snapshot.sorting());
            if (result == null) {
                return null;
            }
            try {
                ByteBuffer view = result.byteBuffer();
                ByteBuffer copy = MemoryUtil.memAlloc(view.remaining());
                copy.put(view.duplicate());
                copy.flip();
                return copy;
            } finally {
                result.close();
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把 6 乘 6 的可达矩阵压成 36 位。
     *
     * <p>
     * 位序与原版 {@code VisibilitySet} 的存放顺序一致，下标是 {@code from.ordinal() + to.ordinal() * 6}，与
     * {@link SimulationVisibility} 读掩码时的算式同源。
     */
    private static long farlands$visibility(VisibilitySet set) {
        long mask = 0L;
        for (Direction from : DIRECTIONS) {
            for (Direction to : DIRECTIONS) {
                if (set.visibilityBetween(from, to)) {
                    mask |= 1L << (from.ordinal() + to.ordinal() * DIRECTIONS.length);
                }
            }
        }
        return mask;
    }

    /**
     * 一个 quad 的着色：先按原版的门选平滑或平坦，再把 tint 乘上去。
     *
     * <p>
     * tint 用 {@code multiplyColor} 而不是覆盖：vanilla 的顺序是先铺方向明暗的灰、再乘 tint，覆盖会把明暗抹平。
     * 无 tint 层时乘 -1，等于不乘。
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
     * 邻块经 getter 按段内局部坐标取，跨段查快照里的邻段，缺段给空气，于是段边界与段内同判据。
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

    /** 释放一份烘制结果的全部层。结果本身可以为 null。 */
    static void release(Build build) {
        if (build == null) {
            return;
        }
        release(build.solid());
        release(build.cutout());
        release(build.translucent());
    }

    /**
     * 释放一份网格的顶点与索引缓冲。
     *
     * <p>
     * 上传成功时由 {@code SimulationGeometry.stage} 释放；结果因代际或版本不符被丢弃时由取走它的一方释放。两块都是
     * 直接内存，漏掉就是显存之外的一处泄漏。
     */
    private static void release(Mesh mesh) {
        if (mesh == null) {
            return;
        }
        MemoryUtil.memFree(mesh.vertices());
        if (mesh.indices() != null) {
            MemoryUtil.memFree(mesh.indices());
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
