package com.inf.farlands.client.render.simulation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import com.inf.farlands.client.simulation.Simulation.SectionKey;
import com.inf.farlands.client.simulation.SimulationMesher;

import org.lwjgl.system.MemoryUtil;

/**
 * 段几何的显存侧：每列一个顶点缓冲，脏了重建，解绑即释放。
 *
 * <p>
 * 顶点是段内局部坐标，所以缓冲与相机无关，移动或转视角都不需要重建。只有段内容变了才会重写一次。
 *
 * <p>
 * 释放走 {@code RenderSystem.queueFencedTask}，等一条 fence 通过再 {@code close}，避免上一帧的命令缓冲仍在
 * 引用被删掉的缓冲。所有方法都在客户端主线程，也就是渲染线程。
 */
public final class SimulationGeometry {

    private static final SimulationGeometry INSTANCE = new SimulationGeometry();

    /** 一列一段的显存记录。 */
    public record Entry(GpuBuffer buffer, int vertexCount) {
    }

    private final Map<SectionKey, Entry> entries = new HashMap<>();

    private SimulationGeometry() {
    }

    public static SimulationGeometry instance() {
        return INSTANCE;
    }

    /** 上传一列的顶点。已有条目先释放。 */
    public void upload(SectionKey columnKey, SimulationMesher.Mesh mesh) {
        Entry previous = this.entries.remove(columnKey);
        if (previous != null) {
            farlands$release(previous.buffer());
        }
        int size = mesh.vertices().length;
        GpuBuffer buffer = RenderSystem.getDevice()
                .createBuffer(() -> "farlands simulation section",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
        ByteBuffer data = MemoryUtil.memAlloc(size);
        try {
            data.put(mesh.vertices());
            data.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
        } finally {
            MemoryUtil.memFree(data);
        }
        this.entries.put(columnKey, new Entry(buffer, mesh.vertexCount()));
    }

    public Entry get(SectionKey columnKey) {
        return this.entries.get(columnKey);
    }

    /** 释放一列。 */
    public void release(SectionKey columnKey) {
        Entry entry = this.entries.remove(columnKey);
        if (entry != null) {
            farlands$release(entry.buffer());
        }
    }

    /**
     * 成批释放。一批只建一条 fence：瞬移或视距变化时会有成百上千列同时解绑，逐列建 fence 会把主线程按在 GPU 上。
     */
    public void releaseAll(Collection<SectionKey> columnKeys) {
        if (columnKeys.isEmpty()) {
            return;
        }
        List<GpuBuffer> buffers = new ArrayList<>(columnKeys.size());
        for (SectionKey columnKey : columnKeys) {
            Entry entry = this.entries.remove(columnKey);
            if (entry != null) {
                buffers.add(entry.buffer());
            }
        }
        if (buffers.isEmpty()) {
            return;
        }
        RenderSystem.queueFencedTask(() -> buffers.forEach(GpuBuffer::close));
    }

    /** 释放全部，退世界与换维度时调。同样只建一条 fence。 */
    public void clear() {
        if (this.entries.isEmpty()) {
            return;
        }
        List<GpuBuffer> buffers = new ArrayList<>(this.entries.size());
        for (Entry entry : this.entries.values()) {
            buffers.add(entry.buffer());
        }
        this.entries.clear();
        RenderSystem.queueFencedTask(() -> buffers.forEach(GpuBuffer::close));
    }

    private static void farlands$release(GpuBuffer buffer) {
        RenderSystem.queueFencedTask(buffer::close);
    }
}
