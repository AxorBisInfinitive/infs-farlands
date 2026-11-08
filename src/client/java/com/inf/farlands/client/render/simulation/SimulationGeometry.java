package com.inf.farlands.client.render.simulation;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.GraphicsWorkarounds;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;

import com.inf.farlands.client.simulation.SimulationMesher;
import com.inf.farlands.client.simulation.SimulationSection;
import com.inf.farlands.client.simulation.SimulationTimings;

import org.lwjgl.system.MemoryUtil;

/**
 * 段几何的显存侧：一块 uber 缓冲，每段占其中一段连续偏移。
 *
 * <p>
 * 照抄原版地形那一族：{@link UberGpuBuffer} 内部按 TLSF 分配，一个 heap 一块 {@link GpuBuffer}，heap 满了自己
 * 再加一个，整 heap 空闲时回收。段顶点不各占一个缓冲，所以同一 heap 里相邻的 draw 绑一次就够，这正是原版
 * {@code SectionUberBuffers} 的形态。
 *
 * <p>
 * 上传分两段：{@link #stage} 把字节拷进 staging，{@link #flush} 一次分配给所有 staged 的段并落地。staging 装不下
 * 时 {@link #stage} 自己先 flush 再重试，因为一帧之内可能积下两三个客户端 tick 的量。
 *
 * <p>
 * 分配落地后由回调把缓冲与起始顶点序号写回段对象，见 {@link SimulationSection#publishGeometry}：渲染侧因此只做
 * 字段读，不查以段号为键的表。
 *
 * <p>
 * 释放只记账，不建 fence。整 heap 空闲时 {@link UberGpuBuffer} 会关掉它，而「整 heap 空闲」意味着该 heap 里每一段
 * 都调过 {@link #drop}，所以只要 {@code drop} 与清几何成对，就不会留下指向已关闭缓冲的引用。
 *
 * <p>
 * 所有方法都在客户端主线程，也就是渲染线程。
 */
public final class SimulationGeometry implements UberGpuBuffer.UploadCallback<SimulationSection> {

    private static final SimulationGeometry INSTANCE = new SimulationGeometry();

    /** 顶点字节数，既是 uber 缓冲的对齐粒度，也是偏移换算成顶点序号的单位。 */
    private static final int VERTEX_BYTES = DefaultVertexFormat.BLOCK.getVertexSize();

    /** 一个 heap 的字节数，照原版地形那一族取 128 MB。 */
    private static final int HEAP_BYTES = 128 * 1024 * 1024;

    /** staging 的字节数，照原版取 32 MB；单段几何远小于它。 */
    private static final int STAGING_BYTES = 32 * 1024 * 1024;

    /**
     * 惰性建：构造器要 {@code RenderSystem.getDevice()}，类初始化时机不受控，不能在静态字段初始化器里建。
     */
    private UberGpuBuffer<SimulationSection> vertexBuffer;

    /** 几何内容的版本，见 {@link #version()}。 */
    private int version;

    /**
     * 自上次落地以来 stage 过几段。
     *
     * <p>
     * 它的唯一作用是让 {@link #flush} 分辨「这一轮真的换了偏移」与「这一轮什么都没有」：后者若也自增版本，
     * 渲染批次缓存每帧都会失效，而缓存恰恰是为静止状态准备的。见 {@link #version()}。
     */
    private int staged;

    private SimulationGeometry() {
    }

    public static SimulationGeometry instance() {
        return INSTANCE;
    }

    /**
     * 把一段的顶点交给 uber 缓冲：先拷进 staging，等下一次 {@link #flush} 分配并落地。
     *
     * <p>
     * 顶点字节是直接内存，拷进 staging 之后由这里释放，所有权不转移。staging 装不下本轮这一次时先 flush 再重试：
     * 一次 flush 会把 staging 清空，而单段字节远小于 staging 容量，所以重试必然成功。
     */
    public void stage(SimulationSection section, SimulationMesher.Mesh mesh) {
        long stageStart = SimulationTimings.begin();
        ByteBuffer data = mesh.vertices();
        int bytes = data.remaining();
        UberGpuBuffer<SimulationSection> buffer = this.farlands$buffer();
        section.setStagedVertexCount(mesh.vertexCount());
        try {
            if (!buffer.addAllocation(section, this, data)) {
                this.flush();
                buffer.addAllocation(section, this, data);
            }
            this.staged++;
        } finally {
            MemoryUtil.memFree(data);
        }
        SimulationTimings.upload(bytes);
        SimulationTimings.end(SimulationTimings.UPLOAD, stageStart);
    }

    /**
     * 把 staging 里全部段分配落地。必须在没有打开 pass 的时候调。
     *
     * <p>
     * 没有 stage 过任何段时直接返回，不动版本：每个偏移都没变，缓存里的 draw 列表仍然有效。这一条是静止状态下
     * 批次缓存能命中的前提。
     */
    public void flush() {
        UberGpuBuffer<SimulationSection> buffer = this.vertexBuffer;
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
     * 分配落地之后的回调：把缓冲与起始顶点序号写回段对象。
     *
     * <p>
     * 取不到分配说明这一段在 flush 之前被 {@link #drop} 掉了，那是正常路径，把几何清掉即可。
     */
    @Override
    public void bufferHasBeenUploaded(SimulationSection section) {
        TlsfAllocator.Allocation allocation = this.vertexBuffer == null ? null
                : this.vertexBuffer.getAllocation(section);
        if (allocation == null) {
            section.clearGeometry();
            return;
        }
        section.publishGeometry(this.vertexBuffer.getGpuBuffer(allocation),
                (int) (allocation.getOffsetFromHeap() / VERTEX_BYTES));
    }

    /** 摘掉一段的几何：清段上的引用并释放它那块偏移。 */
    public void drop(SimulationSection section) {
        section.clearGeometry();
        if (this.vertexBuffer != null) {
            this.vertexBuffer.removeAllocation(section);
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

    /** 释放全部：关 staging 与所有 heap。退世界时调，之后下一次 {@code stage} 重建。 */
    public void clear() {
        UberGpuBuffer<SimulationSection> buffer = this.vertexBuffer;
        if (buffer != null) {
            this.vertexBuffer = null;
            buffer.close();
        }
        this.staged = 0;
        this.version++;
    }

    /** 惰性建 uber 缓冲。 */
    private UberGpuBuffer<SimulationSection> farlands$buffer() {
        UberGpuBuffer<SimulationSection> buffer = this.vertexBuffer;
        if (buffer == null) {
            GpuDevice device = RenderSystem.getDevice();
            buffer = new UberGpuBuffer<>("Farlands simulation sections", GpuBuffer.USAGE_VERTEX, HEAP_BYTES,
                    VERTEX_BYTES, device, STAGING_BYTES, GraphicsWorkarounds.get(device));
            this.vertexBuffer = buffer;
        }
        return buffer;
    }
}
