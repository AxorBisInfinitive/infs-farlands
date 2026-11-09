package com.inf.farlands.client.render.simulation;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.GraphicsWorkarounds;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import com.inf.farlands.client.simulation.SimulationMesher;
import com.inf.farlands.client.simulation.SimulationSection;
import com.inf.farlands.client.simulation.SimulationTimings;

import org.lwjgl.system.MemoryUtil;

/**
 * 段几何的显存侧：一块 uber 缓冲，每段的每层占其中一段连续偏移。
 *
 * <p>
 * 照抄原版地形那一族：{@link UberGpuBuffer} 内部按 TLSF 分配，一个 heap 一块 {@link GpuBuffer}，heap 满了自己
 * 再加一个，整 heap 空闲时回收。顶点不各占一个缓冲，所以同一 heap 里相邻的 draw 绑一次就够。
 *
 * <p>
 * 分配键是「段加层」而不是段：三层各有自己的顶点区。透明层还带一份排过序的自有索引，它单独建成
 * {@code USAGE_INDEX} 的缓冲，随该层的分配落地一起发布，替换与摘除时按 fence 关闭。
 *
 * <p>
 * 上传分两段：{@link #stage} 把字节拷进 staging，{@link #flush} 一次分配给所有 staged 的层并落地。staging 装不下
 * 时 {@link #stage} 自己先 flush 再重试。
 *
 * <p>
 * 释放只记账，不建 fence。整 heap 空闲时 {@link UberGpuBuffer} 会关掉它，而「整 heap 空闲」意味着该 heap 里每一
 * 层都调过 {@link #drop}，所以只要 {@code drop} 与清几何成对，就不会留下指向已关闭缓冲的引用。
 *
 * <p>
 * 所有方法都在客户端主线程，也就是渲染线程。
 */
public final class SimulationGeometry implements UberGpuBuffer.UploadCallback<SimulationGeometry.Key> {

    private static final SimulationGeometry INSTANCE = new SimulationGeometry();

    /** 顶点字节数，既是 uber 缓冲的对齐粒度，也是偏移换算成顶点序号的单位。 */
    private static final int VERTEX_BYTES = DefaultVertexFormat.BLOCK.getVertexSize();

    /** 一个 heap 的字节数，照原版地形那一族取 128 MB。 */
    private static final int HEAP_BYTES = 128 * 1024 * 1024;

    /** staging 的字节数，照原版取 32 MB；单段几何远小于它。 */
    private static final int STAGING_BYTES = 32 * 1024 * 1024;

    /** 分配键：一段的一层。值语义，同一个「段加层」每次构造都相等。 */
    record Key(SimulationSection section, ChunkSectionLayer layer) {
    }

    /** 一层待发布的索引：缓冲、类型与索引数。 */
    private record PendingIndex(GpuBuffer buffer, VertexFormat.IndexType type, int count) {
    }

    /**
     * 惰性建：构造器要 {@code RenderSystem.getDevice()}，类初始化时机不受控，不能在静态字段初始化器里建。
     */
    private UberGpuBuffer<Key> vertexBuffer;

    /** 几何内容的版本，见 {@link #version()}。 */
    private int version;

    /** 自上次落地以来 stage 过几层。 */
    private int staged;

    /** 已 stage、还没随分配落地发布的索引。 */
    private final Map<Key, PendingIndex> pendingIndices = new HashMap<>();

    /** 已发布、还活着的索引缓冲，退世界时统一关闭。 */
    private final Set<GpuBuffer> liveIndices = new HashSet<>();

    private SimulationGeometry() {
    }

    public static SimulationGeometry instance() {
        return INSTANCE;
    }

    /**
     * 把一层的顶点交给 uber 缓冲：先拷进 staging，等下一次 {@link #flush} 分配并落地。
     *
     * <p>
     * 顶点字节是直接内存，拷进 staging 之后由这里释放，所有权不转移。索引同理，先建成显存缓冲登记在案，等分配落地
     * 时与该层几何一起发布。staging 装不下本轮这一次时先 flush 再重试。
     */
    public void stage(SimulationSection section, ChunkSectionLayer layer, SimulationMesher.Mesh mesh) {
        long stageStart = SimulationTimings.begin();
        Key key = new Key(section, layer);
        ByteBuffer data = mesh.vertices();
        int bytes = data.remaining();
        UberGpuBuffer<Key> buffer = this.farlands$buffer();
        section.setStagedVertexCount(layer, mesh.vertexCount());
        PendingIndex index = null;
        if (mesh.indices() != null) {
            index = this.farlands$index(layer, mesh.indices(), mesh.indexType(), mesh.indexCount());
            this.pendingIndices.put(key, index);
        }
        try {
            if (!buffer.addAllocation(key, this, data)) {
                this.flush();
                buffer.addAllocation(key, this, data);
            }
            this.staged++;
        } finally {
            MemoryUtil.memFree(data);
            if (mesh.indices() != null) {
                MemoryUtil.memFree(mesh.indices());
            }
        }
        SimulationTimings.upload(bytes);
        SimulationTimings.end(SimulationTimings.UPLOAD, stageStart);
    }

    /**
     * 把 staging 里全部层分配落地。必须在没有打开 pass 的时候调。
     *
     * <p>
     * 没有 stage 过任何层时直接返回，不动版本：每个偏移都没变，缓存里的 draw 列表仍然有效。这一条是静止状态下
     * 批次缓存能命中的前提。
     */
    public void flush() {
        UberGpuBuffer<Key> buffer = this.vertexBuffer;
        if (buffer == null || this.staged == 0) {
            return;
        }
        long flushStart = SimulationTimings.begin();
        GpuDevice device = RenderSystem.getDevice();
        buffer.uploadStagedAllocations(device, device.createCommandEncoder());
        this.staged = 0;
        this.version++;
        SimulationTimings.end(SimulationTimings.UPLOAD, flushStart);
    }

    /**
     * 分配落地之后的回调：把缓冲、起始顶点序号与索引写回段对象对应层。
     *
     * <p>
     * 取不到分配说明这一层在 flush 之前被 {@link #drop} 掉了，那是正常路径，把该层几何清掉即可。
     */
    @Override
    public void bufferHasBeenUploaded(Key key) {
        TlsfAllocator.Allocation allocation = this.vertexBuffer == null ? null
                : this.vertexBuffer.getAllocation(key);
        if (allocation == null) {
            key.section().clearGeometry(key.layer());
            return;
        }
        PendingIndex index = this.pendingIndices.remove(key);
        int baseVertex = (int) (allocation.getOffsetFromHeap() / VERTEX_BYTES);
        SimulationSection.Geometry previous = key.section().geometry(key.layer());
        GpuBuffer buffer = this.vertexBuffer.getGpuBuffer(allocation);
        key.section().publishGeometry(key.layer(), buffer, baseVertex,
                index == null ? null : index.buffer(), index == null ? null : index.type(),
                index == null ? 0 : index.count());
        if (previous != null && previous.indexBuffer() != null) {
            this.farlands$dropIndex(previous.indexBuffer());
        }
    }

    /**
     * 摘掉一段的全部层：清段上的引用、释放三块偏移与它们的索引。
     *
     * <p>
     * 逐层版叫 {@link #dropLayer}。两者都不要在「随后要重新 stage 同一层」的场合调用：{@code removeAllocation}
     * 会把该键记进 {@code skippedStagedAllocations}，那张表要到下一次整批落地之后才清，期间同一键的 stage 会被静默
     * 跳过，表现为该层几何永远不出现。
     */
    public void drop(SimulationSection section) {
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            this.dropLayer(section, layer);
        }
    }

    /** 摘掉一段的一层。调用方已经决定这一层不再有几何时才用。 */
    public void dropLayer(SimulationSection section, ChunkSectionLayer layer) {
        Key key = new Key(section, layer);
        SimulationSection.Geometry previous = section.geometry(layer);
        section.clearGeometry(layer);
        PendingIndex pending = this.pendingIndices.remove(key);
        if (pending != null) {
            this.farlands$dropIndex(pending.buffer());
        }
        if (previous != null && previous.indexBuffer() != null) {
            this.farlands$dropIndex(previous.indexBuffer());
        }
        if (this.vertexBuffer != null) {
            this.vertexBuffer.removeAllocation(key);
        }
        this.version++;
    }

    /**
     * 几何内容的版本：落地、摘除、清空各加一。
     *
     * <p>
     * 渲染批次缓存靠它判「draw 列表还是不是同一批」。每次落地都会换偏移，所以它在烘制期每帧都变。
     */
    public int version() {
        return this.version;
    }

    /** 释放全部：关 staging、所有 heap 与全部索引。退世界时调，之后下一次 {@code stage} 重建。 */
    public void clear() {
        UberGpuBuffer<Key> buffer = this.vertexBuffer;
        if (buffer != null) {
            this.vertexBuffer = null;
            buffer.close();
        }
        this.pendingIndices.clear();
        for (GpuBuffer index : this.liveIndices) {
            index.close();
        }
        this.liveIndices.clear();
        this.staged = 0;
        this.version++;
    }

    /** 建一份索引缓冲并登记。数据由调用方在返回后释放。 */
    private PendingIndex farlands$index(ChunkSectionLayer layer, ByteBuffer data, VertexFormat.IndexType type,
            int count) {
        GpuBuffer created = RenderSystem.getDevice().createBuffer(
                () -> "farlands simulation indices " + layer, GpuBuffer.USAGE_INDEX, data);
        this.liveIndices.add(created);
        return new PendingIndex(created, type, count);
    }

    /** 按 fence 关闭一份索引，并从存活集合里摘掉。 */
    private void farlands$dropIndex(GpuBuffer index) {
        this.liveIndices.remove(index);
        RenderSystem.queueFencedTask(index::close);
    }

    /** 惰性建 uber 缓冲。 */
    private UberGpuBuffer<Key> farlands$buffer() {
        UberGpuBuffer<Key> buffer = this.vertexBuffer;
        if (buffer == null) {
            GpuDevice device = RenderSystem.getDevice();
            buffer = new UberGpuBuffer<>("Farlands simulation sections", GpuBuffer.USAGE_VERTEX, HEAP_BYTES,
                    VERTEX_BYTES, device, STAGING_BYTES, GraphicsWorkarounds.get(device));
            this.vertexBuffer = buffer;
        }
        return buffer;
    }
}
