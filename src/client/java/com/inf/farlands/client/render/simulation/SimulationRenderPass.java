package com.inf.farlands.client.render.simulation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * 每帧把可见的模拟段组织成一批 draw。
 *
 * <p>
 * 每段一条段 UBO，内容是相机相对的 {@code vec3} 偏移，不用绝对坐标：偏移在 CPU 侧用 long 与 double 算好，
 * 量级落在视距内，落 float 不丢精度。投影与雾由宿主已绑的默认 uniform 提供，块图集与 lightmap 由调用方所在
 * 的渲染 pass 提供，这里只管自己的那条 UBO。
 *
 * <p>
 * 段 UBO 交给 {@link DynamicUniformStorage}：它按 {@code UNIFORM_BUFFER_OFFSET_ALIGNMENT} 取整块宽，并用三缓冲
 * 加 fence 轮转，所以切片偏移天然合法，上一帧在途的 draw 也不会读到被覆盖的块。轮转由 {@link #endFrame()} 推进，
 * 调用点在 {@code LevelRenderer.endFrame}，即一帧的命令全部提交之后。
 *
 * <p>
 * 存储实例惰性建：它的构造器第一句要 {@code RenderSystem.getDevice()}，类初始化时机不受控，不能在静态字段
 * 初始化器里建。{@link #clear()} 之后实例作废，下一次 {@code prepare} 重建。
 *
 * <p>
 * 索引缓冲是自有的，不借 {@code RenderSystem} 的共享顺序缓冲。共享那条由宿主在 {@code renderGroup} 里先取引用、
 * 后使用，我们若在中间请求扩容，宿主持有的是已关闭的旧对象；自有之后这条时序窗口不存在。缓冲内容就是四边形的
 * 顺序索引，长度按本帧最大单 draw，类型按顶点数在 SHORT 与 INT 之间选。
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

    /**
     * 一批绘制：按顶点缓冲分好的组、它们各自的段 UBO 切片，以及合计索引数。
     *
     * <p>
     * 分组不是优化而是必需：{@code VertexArrayCache} 只在相邻两条 draw 用同一块缓冲时才跳过
     * {@code glBindVertexBuffer}，而段的绘制顺序与缓冲归属无关。同一组内所有 draw 共用一块 uber heap，于是每组
     * 只绑一次。
     */
    public record Batch(List<List<RenderPass.Draw<GpuBufferSlice[]>>> groups, GpuBufferSlice[] infos,
            int indexCount) {
    }

    /**
     * 一段的段 UBO。字段顺序即着色器里 {@code SimSection} 的声明顺序，写入走 std140。
     *
     * <p>
     * {@code modelView} 是所有段共用的同一条旋转矩阵，{@code offset} 是相机相对量。
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

    /** 缓存下来的 draw 分组。顶点缓冲与索引缓冲在几何不变时不变，切片由 {@code batch.infos()} 现给。 */
    private List<List<RenderPass.Draw<GpuBufferSlice[]>>> cachedGroups;

    private int cachedIndices;

    /** 自有的索引缓冲，内容是 0 起的四边形顺序索引。容量按索引数记。 */
    private GpuBuffer indexBuffer;
    private VertexFormat.IndexType indexType;
    private int indexCapacity;

    private SimulationRenderPass() {
    }

    /**
     * 在渲染 pass 之外准备本帧的批次：筛可见段、写段 UBO、组装 draw 列表。
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
        // 遍历源是视锥裁剪过的集：传播与裁剪都在 SimulationVisibility 里，裁剪只在图发布或相机转动跨 2 度时重跑。
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
            GpuBufferSlice[] hitSlices = this.farlands$storage().writeUniforms(this.cachedInfos);
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
        List<Simulation.SnapSection> visible = new ArrayList<>(sections.size());
        List<SimulationSection.Geometry> geometries = new ArrayList<>(sections.size());
        int indices = 0;
        int maxDrawIndices = 0;
        for (Simulation.SnapSection section : sections) {
            SimulationSection.Geometry geometry = section.section().geometry();
            if (geometry == null || geometry.vertexCount() <= 0) {
                continue;
            }
            int drawIndices = geometry.indexCount();
            indices += drawIndices;
            if (drawIndices > maxDrawIndices) {
                maxDrawIndices = drawIndices;
            }
            visible.add(section);
            geometries.add(geometry);
        }
        int count = visible.size();
        SimulationTimings.end(SimulationTimings.ITERATE, iterateStart);
        if (count == 0) {
            return;
        }
        this.farlands$ensureIndices(maxDrawIndices);
        SectionInfo[] infos = new SectionInfo[count];
        for (int i = 0; i < count; i++) {
            Simulation.SnapSection section = visible.get(i);
            infos[i] = new SectionInfo(view, 1.0F, atlasWidth, atlasHeight,
                    (float) ((double) (section.originX() - camAnchorX) - camLocalX),
                    (float) ((double) (section.originY() - camAnchorY) - camLocalY),
                    (float) ((double) (section.originZ() - camAnchorZ) - camLocalZ));
        }
        long uboStart = SimulationTimings.begin();
        GpuBufferSlice[] slices = this.farlands$storage().writeUniforms(infos);
        SimulationTimings.end(SimulationTimings.UBO, uboStart);
        Map<GpuBuffer, List<RenderPass.Draw<GpuBufferSlice[]>>> byBuffer = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            SimulationSection.Geometry geometry = geometries.get(i);
            int slot = i;
            byBuffer.computeIfAbsent(geometry.buffer(), owner -> new ArrayList<>())
                    .add(new RenderPass.Draw<>(0, geometry.buffer(), this.indexBuffer, this.indexType, 0,
                            geometry.indexCount(), geometry.baseVertex(),
                            (all, uploader) -> uploader.upload("SimSection", all[slot])));
        }
        List<List<RenderPass.Draw<GpuBufferSlice[]>>> groups = List.copyOf(byBuffer.values());
        this.cachedInfos = infos;
        this.cachedGroups = groups;
        this.cachedIndices = indices;
        this.batch = new Batch(groups, slices, indices);
    }

    /**
     * 缓存键比对：可见集内容、几何、相机位姿、图集尺寸四项全等才算命中。
     *
     * <p>
     * 位姿三项缺一不可：视锥测试依赖位置与朝向，段 UBO 的偏移依赖位置、`modelView` 依赖朝向。图集尺寸进 UBO，
     * 资源重载换图集时会连带清几何、版本自增，所以它是第二道闸而不是主闸。
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
     * 保证自有索引缓冲能装下本帧最大的一条 draw。
     *
     * <p>
     * 类型按顶点数选，判据是 {@code IndexType.least}：顶点数不超过 65535 用 SHORT，否则 INT。用索引数去比会早
     * 一档切宽，用 SHORT 装超限的缓冲会在驱动层静默截断。长度取 6 的整数倍，与四边形模式的步长对齐；不足时
     * 按请求量翻倍再对齐，减少大段出现时反复重建。
     */
    private void farlands$ensureIndices(int neededIndices) {
        if (neededIndices <= 0 || neededIndices <= this.indexCapacity) {
            return;
        }
        int target = ((Math.max(neededIndices, this.indexCapacity * 2) + 5) / 6) * 6;
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
