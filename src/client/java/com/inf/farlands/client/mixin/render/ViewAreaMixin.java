package com.inf.farlands.client.mixin.render;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 客户端渲染网格的竖直环绕。
 *
 * vanilla 把网格竖直槽位锚在 level.getMinSectionY()：createSections 的初始节点、repositionCamera
 * 的 newSectionY、getRenderSection 与 containsSection 的范围判定都读它。ClientLevel 那两个值是
 * 维度范围 -4 与 19，于是网格恒为 section [-4, 30]，低于 -4 的 section 在 containsSection 处被拒，
 * getRenderSection 返回 null，遮挡图的洪泛到不了那里，octree 里没有它，最终不渲染。地形按窗口段
 * 生成，-64 以下有数据，所以这一段是实打实缺失的。
 *
 * 改成与 vanilla 的 XZ 同一套环绕：查表是 slot = floorMod(sectionY, gridSizeY)，分配时把以相机为
 * 中心的连续 gridSizeY 段铺进这 gridSizeY 个槽位。XZ 与水平窗口判定保持原样。
 *
 * repositionCamera 只在相机跨 section 时由 LevelRenderer 调用，它结尾的 invalidate 因此不再每帧
 * 触发。每帧要做的窗口拉正已移到 render/LevelRendererMixin。
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {

    @Shadow
    protected LevelRenderer levelRenderer;

    @Shadow
    protected int sectionGridSizeX;

    @Shadow
    protected int sectionGridSizeY;

    @Shadow
    protected int sectionGridSizeZ;

    @Shadow
    private int viewDistance;

    @Shadow
    private SectionPos cameraSectionPos;

    @Shadow
    public SectionRenderDispatcher.RenderSection[] sections;

    @Overwrite
    protected void setViewDistance(int renderDistanceChunks) {
        int horizontal = renderDistanceChunks * 2 + 1;
        this.sectionGridSizeX = horizontal;
        this.sectionGridSizeY = FarlandsConfig.verticalSimulationDistance * 2 + 1;
        this.sectionGridSizeZ = horizontal;
        this.viewDistance = renderDistanceChunks;
    }

    /**
     * 第 gridY 个槽位装 low + floorMod(gridY - low, gridSizeY) 段，low = camSectionY - half。
     * gridSizeY 个槽位恰好覆盖 [cam-half, cam+half] 连续段，恒含相机所在段，与查表的
     * floorMod(sectionY, gridSizeY) 互为逆运算。
     */
    @Overwrite
    public void repositionCamera(SectionPos cameraSectionPos) {
        int half = this.sectionGridSizeY / 2;
        int low = cameraSectionPos.y() - half;
        for (int gridX = 0; gridX < this.sectionGridSizeX; gridX++) {
            int lowestX = cameraSectionPos.x() - this.viewDistance;
            int newSectionX = lowestX + Math.floorMod(gridX - lowestX, this.sectionGridSizeX);
            for (int gridZ = 0; gridZ < this.sectionGridSizeZ; gridZ++) {
                int lowestZ = cameraSectionPos.z() - this.viewDistance;
                int newSectionZ = lowestZ + Math.floorMod(gridZ - lowestZ, this.sectionGridSizeZ);
                for (int gridY = 0; gridY < this.sectionGridSizeY; gridY++) {
                    int newSectionY = low + Math.floorMod(gridY - low, this.sectionGridSizeY);
                    SectionRenderDispatcher.RenderSection section = this.sections[farlands$gridIndex(gridX, gridY, gridZ)];
                    long node = SectionPos.asLong(newSectionX, newSectionY, newSectionZ);
                    if (section.getSectionNode() != node) {
                        section.setSectionNode(node);
                    }
                }
            }
        }
        this.cameraSectionPos = cameraSectionPos;
        this.levelRenderer.getSectionOcclusionGraph().invalidate();
    }

    /** 竖直窗口判定改成相机段加减半高，XZ 判定与原版一致；可表示段界之外一律不认。 */
    @Overwrite
    private boolean containsSection(int sectionX, int sectionY, int sectionZ) {
        // 界外段拿不到盒内的叶，却会以回绕后的方块坐标占住盒内最外那一格的叶位，把真实段顶掉。它们的
        // 方块坐标本身不可表示，客户端不认它们。判据取可表示段界而不是可玩段界：段 134217727 属于
        // 要求里「尽量工作」的那一格，盒子为它让出了一整段。
        if (!WorldBounds.inSectionAbsolute(sectionX) || !WorldBounds.inSectionAbsolute(sectionY)
                || !WorldBounds.inSectionAbsolute(sectionZ)) {
            return false;
        }
        int half = this.sectionGridSizeY / 2;
        int camSectionY = this.cameraSectionPos.y();
        if (sectionY < camSectionY - half || sectionY > camSectionY + half) {
            return false;
        }
        return sectionX < this.cameraSectionPos.x() - this.viewDistance
                || sectionX > this.cameraSectionPos.x() + this.viewDistance ? false
                        : sectionZ >= this.cameraSectionPos.z() - this.viewDistance
                                && sectionZ <= this.cameraSectionPos.z() + this.viewDistance;
    }

    /** 与 vanilla 的 getSectionIndex 同布局，竖直下标换成 floorMod(sectionY, gridSizeY)。 */
    @Overwrite
    private SectionRenderDispatcher.RenderSection getRenderSection(int sectionX, int sectionY, int sectionZ) {
        if (!this.containsSection(sectionX, sectionY, sectionZ)) {
            return null;
        }
        int x = Math.floorMod(sectionX, this.sectionGridSizeX);
        int y = Math.floorMod(sectionY, this.sectionGridSizeY);
        int z = Math.floorMod(sectionZ, this.sectionGridSizeZ);
        return this.sections[farlands$gridIndex(x, y, z)];
    }

    @Unique
    private int farlands$gridIndex(int x, int y, int z) {
        return (z * this.sectionGridSizeY + y) * this.sectionGridSizeX + x;
    }
}
