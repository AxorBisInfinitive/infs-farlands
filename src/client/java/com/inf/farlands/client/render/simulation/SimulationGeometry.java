package com.inf.farlands.client.render.simulation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.inf.farlands.client.simulation.Simulation.SectionKey;
import com.inf.farlands.client.simulation.SimulationMesher;

import org.lwjgl.system.MemoryUtil;

/**
 * 段几何的显存侧：每列一个顶点缓冲，脏了重写，解绑即归还。
 *
 * <p>
 * 顶点是段内局部坐标，所以缓冲与相机无关，移动或转视角都不需要重建。只有段内容变了才会重写一次。
 *
 * <p>
 * 缓冲按尺寸分档池化。烘制期一秒要换两三千个缓冲，每个都走一次 {@code glBufferStorage} 的话，驱动侧的分配与释放
 * 就是渲染线程的主要开销；池化之后同一档的缓冲被反复重用，只做一次 {@code writeToBuffer}。档位向上取到 2 的幂，
 * 最小 4 KB、最大 512 KB，超过最大档的按实际字节一次性分配、不进池。
 *
 * <p>
 * 归还走 {@code RenderSystem.queueFencedTask}，等一条 fence 通过再入池：fence 在入队那一刻建立，它通过就说明
 * 之前提交的命令都已执行完，缓冲此刻确实空闲。所有方法都在客户端主线程，也就是渲染线程。
 */
public final class SimulationGeometry {

    private static final SimulationGeometry INSTANCE = new SimulationGeometry();

    /** 池化的最小档，4 KB。 */
    private static final int MIN_SHIFT = 12;

    /** 池化的最大档，512 KB。超过它的按实际字节分配、不进池。 */
    private static final int MAX_SHIFT = 19;

    /** 每档最多留几个空闲缓冲。超出的直接关掉，免得空闲表一直涨。 */
    private static final int FREE_PER_CLASS = 256;

    /** 一列一段的显存记录。{@code shift} 是池化档位，{@code -1} 表示未池化。 */
    public record Entry(GpuBuffer buffer, int vertexCount, int shift) {
    }

    private final Map<SectionKey, Entry> entries = new HashMap<>();

    /** 空闲缓冲，下标是档位减 {@link #MIN_SHIFT}。只在客户端主线程读写。 */
    private final List<List<GpuBuffer>> free = new ArrayList<>(MAX_SHIFT - MIN_SHIFT + 1);

    private SimulationGeometry() {
        for (int i = MIN_SHIFT; i <= MAX_SHIFT; i++) {
            this.free.add(new ArrayList<>());
        }
    }

    public static SimulationGeometry instance() {
        return INSTANCE;
    }

    /**
     * 上传一列的顶点。已有条目先归还。
     *
     * <p>
     * 顶点缓冲是直接内存，写完由这里释放。池化路径只做 {@code writeToBuffer}，分配路径才建新缓冲；两条路径的用途
     * 标志都带 {@code USAGE_COPY_DST}，因为 {@code writeToBuffer} 要求目标缓冲带这一位。
     */
    public void upload(SectionKey columnKey, SimulationMesher.Mesh mesh) {
        Entry previous = this.entries.remove(columnKey);
        if (previous != null) {
            this.farlands$release(previous);
        }
        ByteBuffer data = mesh.vertices();
        int bytes = data.remaining();
        int shift = farlands$shift(bytes);
        GpuBuffer buffer = shift < 0 ? null : this.farlands$take(shift);
        try {
            if (buffer == null) {
                long size = shift < 0 ? bytes : 1L << shift;
                buffer = RenderSystem.getDevice().createBuffer(() -> "farlands simulation section",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
            }
            GpuBufferSlice slice = buffer.slice(0L, bytes);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(slice, data);
        } finally {
            MemoryUtil.memFree(data);
        }
        this.entries.put(columnKey, new Entry(buffer, mesh.vertexCount(), shift));
    }

    public Entry get(SectionKey columnKey) {
        return this.entries.get(columnKey);
    }

    /** 释放一列。 */
    public void release(SectionKey columnKey) {
        Entry entry = this.entries.remove(columnKey);
        if (entry != null) {
            this.farlands$release(entry);
        }
    }

    /**
     * 成批释放。一批只建一条 fence：瞬移或视距变化时会有成百上千列同时解绑，逐列建 fence 会把主线程按在 GPU 上。
     */
    public void releaseAll(Collection<SectionKey> columnKeys) {
        if (columnKeys.isEmpty()) {
            return;
        }
        List<Entry> released = new ArrayList<>(columnKeys.size());
        for (SectionKey columnKey : columnKeys) {
            Entry entry = this.entries.remove(columnKey);
            if (entry != null) {
                released.add(entry);
            }
        }
        if (released.isEmpty()) {
            return;
        }
        RenderSystem.queueFencedTask(() -> {
            for (Entry entry : released) {
                this.farlands$recycle(entry);
            }
        });
    }

    /** 释放全部，退世界与换维度时调。同样只建一条 fence，空闲表一并清掉。 */
    public void clear() {
        List<Entry> released = new ArrayList<>(this.entries.size());
        for (Entry entry : this.entries.values()) {
            released.add(entry);
        }
        this.entries.clear();
        if (!released.isEmpty()) {
            RenderSystem.queueFencedTask(() -> {
                for (Entry entry : released) {
                    entry.buffer().close();
                }
            });
        }
        for (List<GpuBuffer> bucket : this.free) {
            for (GpuBuffer buffer : bucket) {
                buffer.close();
            }
            bucket.clear();
        }
    }

    /** 归还一条记录：池化的进空闲表，未池化的关掉。 */
    private void farlands$release(Entry entry) {
        if (entry.shift() < 0) {
            RenderSystem.queueFencedTask(entry.buffer()::close);
            return;
        }
        RenderSystem.queueFencedTask(() -> this.farlands$recycle(entry));
    }

    /** fence 之后调用：进该档的空闲表，表满了就关掉。 */
    private void farlands$recycle(Entry entry) {
        int shift = entry.shift();
        if (shift < 0) {
            entry.buffer().close();
            return;
        }
        List<GpuBuffer> bucket = this.free.get(shift - MIN_SHIFT);
        if (bucket.size() >= FREE_PER_CLASS) {
            entry.buffer().close();
            return;
        }
        bucket.add(entry.buffer());
    }

    /** 取一个该档的空闲缓冲，没有则 null。 */
    private GpuBuffer farlands$take(int shift) {
        List<GpuBuffer> bucket = this.free.get(shift - MIN_SHIFT);
        int last = bucket.size() - 1;
        return last < 0 ? null : bucket.remove(last);
    }

    /** 字节数对应的池化档位，超出最大档返回 {@code -1}。 */
    private static int farlands$shift(int bytes) {
        if (bytes <= 1 << MIN_SHIFT) {
            return MIN_SHIFT;
        }
        int shift = 32 - Integer.numberOfLeadingZeros(bytes - 1);
        return shift > MAX_SHIFT ? -1 : shift;
    }
}
