package com.inf.farlands.client.render.simulation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

import com.inf.farlands.client.render.AnchoredFrustum;
import com.inf.farlands.client.render.ExactCameraOrigin;
import com.inf.farlands.client.simulation.Simulation;
import com.inf.farlands.client.simulation.SimulationHolder;
import com.inf.farlands.client.simulation.SimulationSection;
import com.inf.farlands.client.simulation.SimulationTimings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * 每帧把可见的模拟段组织成三层 draw。
 *
 * <p>
 * 分层的判据只有一个：实体层不带 {@code discard}，深度测试因此能在着色前丢掉被挡住的片元；镂空层带阈值，只放
 * 需要镂空的方块；透明层单独一遍，由远到近画。三条管线见 {@link SimulationPipelines}。
 *
 * <p>
 * 每段一条段 UBO，内容是相机相对的 {@code vec3} 偏移：偏移在 CPU 侧用 long 与 double 算好，量级落在视距内，
 * 落 float 不丢精度。一段的三层共用这一条 UBO，与原版 {@code ChunkSectionInfo} 的用法一致。
 *
 * <p>
 * 索引：实体与镂空层用一份共享顺序索引，靠 {@code baseVertex} 定位本段；透明层用它自己排过序的索引。
 *
 * <p>
 * 段 UBO 交给 {@link DynamicUniformStorage}：它按 {@code UNIFORM_BUFFER_OFFSET_ALIGNMENT} 取整块宽，并用三缓冲
 * 加 fence 轮转。轮转由 {@link #endFrame()} 推进，调用点在 {@code LevelRenderer.endFrame}。
 */
public final class SimulationRenderPass {

    /** 一条段 UBO 的字节数，按 std140 算：mat4 64 加 float 4 加 ivec2 8 加 vec3 16。 */
    private static final int INFO_BYTES = new Std140SizeCalculator()
            .putMat4f()
            .putFloat()
            .putIVec2()
            .putVec3()
            .get();

    /** 一条环形存储容纳的段数上限；超过时由存储自己扩容并打一条日志。 */
    private static final int CAPACITY = 512;

    /** 三层，顺序即 {@link ChunkSectionLayer} 的枚举序。 */
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

    /**
     * 一批绘制：按层与顶点缓冲分好的组、每段的段 UBO 切片，以及合计索引数。
     *
     * <p>
     * 分组不是优化而是必需：{@code VertexArrayCache} 只在相邻两条 draw 用同一块缓冲时才跳过
     * {@code glBindVertexBuffer}。同一组内所有 draw 共用一块 uber heap，每组只绑一次。
     */
    public record Batch(Map<ChunkSectionLayer, List<List<RenderPass.Draw<GpuBufferSlice[]>>>> groups,
            GpuBufferSlice[] infos, int indexCount) {

        /** 该层的绘制组，没有则空表。 */
        public List<List<RenderPass.Draw<GpuBufferSlice[]>>> groups(ChunkSectionLayer layer) {
            return this.groups.getOrDefault(layer, List.of());
        }
    }

    /**
     * 一段的段 UBO。字段顺序即着色器里 {@code SimSection} 的声明顺序，写入走 std140。
     */
    public record SectionInfo(Matrix4fc modelView, float visibility, int atlasWidth, int atlasHeight,
            float offsetX, float offsetY, float offsetZ) implements DynamicUniformStorage.DynamicUniform {

        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                    .putMat4f(this.modelView)
                    .putFloat(this.visibility)
                    .putIVec2(this.atlasWidth, this.atlasHeight)
                    .putVec3(this.offsetX, this.offsetY, this.offsetZ);
        }
    }

    /** 唯一实例：显存只该有一份，退世界时由 {@code simulation.MinecraftMixin} 调 {@link #clear}。 */
    public static final SimulationRenderPass INSTANCE = new SimulationRenderPass();

    private DynamicUniformStorage<SectionInfo> uniforms;

    /** 本帧准备好的批次，没有可见段时为 null。 */
    private Batch batch;

    /** 缓存是否有效。失效条件是可见集内容、几何、相机位姿、图集尺寸任一项变化。 */
    private boolean cacheValid;

    private int cacheVisibleVersion = -1;

    private int cacheGeometryVersion = -1;

    private long cacheAnchorX;

    private long cacheAnchorY;

    private long cacheAnchorZ;

    private double cacheLocalX;

    private double cacheLocalY;

    private double cacheLocalZ;

    private final Matrix4f cacheView = new Matrix4f();

    private int cacheAtlasWidth = -1;

    private int cacheAtlasHeight = -1;

    /** 缓存下来的段 UBO 输入。相机不变时偏移与矩阵都不变，所以只需重写、不必重建。 */
    private SectionInfo[] cachedInfos;

    /** 缓存下来的三层 draw 分组。 */
    private Map<ChunkSectionLayer, List<List<RenderPass.Draw<GpuBufferSlice[]>>>> cachedGroups;

    private int cachedIndices;

    /** 自有的共享顺序索引，给没有自有索引的层用。 */
    private GpuBuffer indexBuffer;
    private VertexFormat.IndexType indexType;
    private int indexCapacity;

    private SimulationRenderPass() {
    }

    /**
     * 在渲染 pass 之外准备本帧的批次：筛可见段、写段 UBO、组装三层 draw 列表。
     *
     * <p>
     * 必须在没有打开 pass 的时候调。{@code writeUniforms} 内部要 {@code mapBuffer}，pass 打开期间会直接抛
     * {@code Close the existing render pass before performing additional commands}。
     */
    public void prepare(GpuTextureView atlas, CameraRenderState camera, ExactCameraOrigin origin) {
        this.batch = null;
        if (atlas == null) {
            return;
        }
        Simulation simulation = ((SimulationHolder) Minecraft.getInstance()).simulation();
        long camAnchorX;
        long camAnchorY;
        long camAnchorZ;
        double camLocalX;
        double camLocalY;
        double camLocalZ;
        if (origin != null && origin.farlands$cameraValid()) {
            camAnchorX = origin.farlands$cameraAnchor(0);
            camAnchorY = origin.farlands$cameraAnchor(1);
            camAnchorZ = origin.farlands$cameraAnchor(2);
            camLocalX = origin.farlands$cameraLocal(0);
            camLocalY = origin.farlands$cameraLocal(1);
            camLocalZ = origin.farlands$cameraLocal(2);
        } else {
            // 退路：把相机的 double 位置拆成锚点加局部量，两项依次相减，与直接减相机逐位相同。
            Vec3 cameraPos = camera.pos;
            camAnchorX = Mth.lfloor(cameraPos.x);
            camAnchorY = Mth.lfloor(cameraPos.y);
            camAnchorZ = Mth.lfloor(cameraPos.z);
            camLocalX = cameraPos.x - (double) camAnchorX;
            camLocalY = cameraPos.y - (double) camAnchorY;
            camLocalZ = cameraPos.z - (double) camAnchorZ;
        }
        // 先把此前 stage 的几何落地：段上的几何引用在回调里更新，之后遍历读到的都是本帧的偏移。
        SimulationGeometry.instance().flush();
        AnchoredFrustum frustum = camera.initialized && camera.cullFrustum instanceof AnchoredFrustum candidate
                ? candidate
                : null;
        long rebuildStart = SimulationTimings.begin();
        List<Simulation.SnapSection> sections = simulation.visibleSections(camAnchorX, camAnchorY, camAnchorZ,
                camLocalX, camLocalY, camLocalZ, camera.xRot, camera.yRot, frustum);
        SimulationTimings.end(SimulationTimings.REBUILD, rebuildStart);
        SimulationTimings.visible(sections.size());
        int atlasWidth = atlas.getWidth(0);
        int atlasHeight = atlas.getHeight(0);
        Matrix4f view = new Matrix4f(camera.viewRotationMatrix);
        int visibleVersion = simulation.visibleVersion();
        int geometryVersion = SimulationGeometry.instance().version();
        if (this.farlands$cacheHit(visibleVersion, geometryVersion, camAnchorX, camAnchorY, camAnchorZ, camLocalX,
                camLocalY, camLocalZ, view, atlasWidth, atlasHeight)) {
            SimulationTimings.cache(true);
            if (this.cachedGroups == null) {
                return;
            }
            long uboStart = SimulationTimings.begin();
            SimulationTimings.enterUniforms();
            GpuBufferSlice[] hitSlices;
            try {
                hitSlices = this.farlands$storage().writeUniforms(this.cachedInfos);
            } finally {
                SimulationTimings.exitUniforms();
            }
            SimulationTimings.end(SimulationTimings.UBO, uboStart);
            this.batch = new Batch(this.cachedGroups, hitSlices, this.cachedIndices);
            return;
        }
        this.farlands$storeCache(visibleVersion, geometryVersion, camAnchorX, camAnchorY, camAnchorZ, camLocalX,
                camLocalY, camLocalZ, view, atlasWidth, atlasHeight);
        this.cachedGroups = null;
        this.cachedInfos = null;
        this.cachedIndices = 0;
        long iterateStart = SimulationTimings.begin();
        if (sections.isEmpty()) {
            SimulationTimings.end(SimulationTimings.ITERATE, iterateStart);
            return;
        }
        List<Simulation.SnapSection> drawn = new ArrayList<>(sections.size());
        for (Simulation.SnapSection section : sections) {
            SimulationSection body = section.section();
            if (body.geometry(ChunkSectionLayer.SOLID) == null && body.geometry(ChunkSectionLayer.CUTOUT) == null
                    && body.geometry(ChunkSectionLayer.TRANSLUCENT) == null) {
                continue;
            }
            drawn.add(section);
        }
        int count = drawn.size();
        SimulationTimings.end(SimulationTimings.ITERATE, iterateStart);
        if (count == 0) {
            return;
        }
        this.farlands$ensureIndices(drawn);
        SectionInfo[] infos = new SectionInfo[count];
        for (int i = 0; i < count; i++) {
            Simulation.SnapSection section = drawn.get(i);
            infos[i] = new SectionInfo(view, 1.0F, atlasWidth, atlasHeight,
                    (float) ((double) (section.originX() - camAnchorX) - camLocalX),
                    (float) ((double) (section.originY() - camAnchorY) - camLocalY),
                    (float) ((double) (section.originZ() - camAnchorZ) - camLocalZ));
        }
        long uboStart = SimulationTimings.begin();
        SimulationTimings.enterUniforms();
        GpuBufferSlice[] slices;
        try {
            slices = this.farlands$storage().writeUniforms(infos);
        } finally {
            SimulationTimings.exitUniforms();
        }
        SimulationTimings.end(SimulationTimings.UBO, uboStart);
        int indices = 0;
        Map<ChunkSectionLayer, Map<GpuBuffer, List<RenderPass.Draw<GpuBufferSlice[]>>>> byLayer = new EnumMap<>(
                ChunkSectionLayer.class);
        for (int i = 0; i < count; i++) {
            SimulationSection body = drawn.get(i).section();
            int slot = i;
            for (ChunkSectionLayer layer : LAYERS) {
                SimulationSection.Geometry geometry = body.geometry(layer);
                if (geometry == null || geometry.vertexCount() <= 0) {
                    continue;
                }
                indices += geometry.indexCount();
                boolean custom = geometry.indexBuffer() != null;
                byLayer.computeIfAbsent(layer, owner -> new LinkedHashMap<>())
                        .computeIfAbsent(geometry.buffer(), owner -> new ArrayList<>())
                        .add(new RenderPass.Draw<>(0, geometry.buffer(),
                                custom ? geometry.indexBuffer() : this.indexBuffer,
                                custom ? geometry.indexType() : this.indexType, 0, geometry.indexCount(),
                                geometry.baseVertex(), (all, uploader) -> uploader.upload("SimSection", all[slot])));
            }
        }
        Map<ChunkSectionLayer, List<List<RenderPass.Draw<GpuBufferSlice[]>>>> groups = new EnumMap<>(
                ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, Map<GpuBuffer, List<RenderPass.Draw<GpuBufferSlice[]>>>> entry : byLayer
                .entrySet()) {
            groups.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        this.cachedInfos = infos;
        this.cachedGroups = groups;
        this.cachedIndices = indices;
        this.batch = new Batch(groups, slices, indices);
    }

    /**
     * 缓存键比对：可见集内容、几何、相机位姿、图集尺寸四项全等才算命中。
     *
     * <p>
     * 位姿三项缺一不可：视锥测试依赖位置与朝向，段 UBO 的偏移依赖位置、{@code modelView} 依赖朝向。图集尺寸进
     * UBO，资源重载换图集时会连带清几何、版本自增，所以它是第二道闸而不是主闸。
     */
    private boolean farlands$cacheHit(int visibleVersion, int geometryVersion, long anchorX, long anchorY,
            long anchorZ, double localX, double localY, double localZ, Matrix4f view, int atlasWidth,
            int atlasHeight) {
        return this.cacheValid && this.cacheVisibleVersion == visibleVersion
                && this.cacheGeometryVersion == geometryVersion && this.cacheAnchorX == anchorX
                && this.cacheAnchorY == anchorY && this.cacheAnchorZ == anchorZ && this.cacheLocalX == localX
                && this.cacheLocalY == localY && this.cacheLocalZ == localZ
                && this.cacheView.equals(view, 0.0F) && this.cacheAtlasWidth == atlasWidth
                && this.cacheAtlasHeight == atlasHeight;
    }

    private void farlands$storeCache(int visibleVersion, int geometryVersion, long anchorX, long anchorY,
            long anchorZ, double localX, double localY, double localZ, Matrix4f view, int atlasWidth,
            int atlasHeight) {
        this.cacheValid = true;
        this.cacheVisibleVersion = visibleVersion;
        this.cacheGeometryVersion = geometryVersion;
        this.cacheAnchorX = anchorX;
        this.cacheAnchorY = anchorY;
        this.cacheAnchorZ = anchorZ;
        this.cacheLocalX = localX;
        this.cacheLocalY = localY;
        this.cacheLocalZ = localZ;
        this.cacheView.set(view);
        this.cacheAtlasWidth = atlasWidth;
        this.cacheAtlasHeight = atlasHeight;
    }

    /** 本帧准备好的批次，没有则为 null。渲染期取用。 */
    public Batch batch() {
        return this.batch;
    }

    /**
     * 推进一步段 UBO 的轮转，一帧一次，由 {@code LevelRenderer.endFrame} 调。
     *
     * <p>
     * 必须在整帧的命令都提交之后：{@code DynamicUniformStorage} 的 fence 建在轮转那一刻，建早了就不覆盖本帧的
     * draw，三帧后复用同一条缓冲时就会读到还在被 GPU 读的块。
     */
    public void endFrame() {
        if (this.uniforms != null) {
            this.uniforms.endFrame();
        }
    }

    /** 释放本类持有的显存。关服或换世界时调，之后实例作废，下一次 {@code prepare} 重建。 */
    public void clear() {
        this.batch = null;
        this.cacheValid = false;
        this.cachedGroups = null;
        this.cachedInfos = null;
        this.cachedIndices = 0;
        if (this.indexBuffer != null) {
            GpuBuffer buffer = this.indexBuffer;
            this.indexBuffer = null;
            this.indexType = null;
            this.indexCapacity = 0;
            RenderSystem.queueFencedTask(buffer::close);
        }
        if (this.uniforms != null) {
            this.uniforms.close();
            this.uniforms = null;
        }
    }

    /**
     * 保证自有共享索引缓冲能装下本帧最大的一条共享 draw。
     *
     * <p>
     * 只统计没有自有索引的层，透明层那批自带索引，不能算进来。类型按顶点数选，判据是 {@code IndexType.least}：
     * 顶点数不超过 65535 用 SHORT，否则 INT。长度取 6 的整数倍，与四边形模式的步长对齐。
     */
    private void farlands$ensureIndices(List<Simulation.SnapSection> drawn) {
        int needed = 0;
        for (Simulation.SnapSection section : drawn) {
            for (ChunkSectionLayer layer : LAYERS) {
                SimulationSection.Geometry geometry = section.section().geometry(layer);
                if (geometry != null && geometry.indexBuffer() == null && geometry.indexCount() > needed) {
                    needed = geometry.indexCount();
                }
            }
        }
        if (needed <= 0 || needed <= this.indexCapacity) {
            return;
        }
        int target = ((Math.max(needed, this.indexCapacity * 2) + 5) / 6) * 6;
        int vertexCount = target / 6 * 4;
        VertexFormat.IndexType type = VertexFormat.IndexType.least(vertexCount);
        ByteBuffer data = MemoryUtil.memAlloc(target * type.bytes);
        GpuBuffer created;
        try {
            int quads = target / 6;
            for (int quad = 0; quad < quads; quad++) {
                int base = quad * 4;
                if (type == VertexFormat.IndexType.INT) {
                    data.putInt(base).putInt(base + 1).putInt(base + 2)
                            .putInt(base + 2).putInt(base + 3).putInt(base);
                } else {
                    data.putShort((short) base).putShort((short) (base + 1)).putShort((short) (base + 2))
                            .putShort((short) (base + 2)).putShort((short) (base + 3)).putShort((short) base);
                }
            }
            data.flip();
            created = RenderSystem.getDevice().createBuffer(() -> "farlands simulation indices",
                    GpuBuffer.USAGE_INDEX, data);
        } finally {
            MemoryUtil.memFree(data);
        }
        GpuBuffer previous = this.indexBuffer;
        this.indexBuffer = created;
        this.indexType = type;
        this.indexCapacity = target;
        if (previous != null) {
            RenderSystem.queueFencedTask(previous::close);
        }
    }

    /** 惰性建存储：构造器要设备，不能在静态初始化里跑。 */
    private DynamicUniformStorage<SectionInfo> farlands$storage() {
        DynamicUniformStorage<SectionInfo> storage = this.uniforms;
        if (storage == null) {
            storage = new DynamicUniformStorage<>("Farlands simulation sections UBO", INFO_BYTES, CAPACITY);
            this.uniforms = storage;
        }
        return storage;
    }
}
