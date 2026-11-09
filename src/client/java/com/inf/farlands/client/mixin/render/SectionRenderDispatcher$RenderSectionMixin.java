package com.inf.farlands.client.mixin.render;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 视距边缘 section 永久不渲染的修复。
 *
 * vanilla 的 hasAllNeighbors 检查该 section 周围 8 个 chunk 是否都已在客户端加载，
 * 26.1.2 的三个消费点都在渲染可见性链上：
 * LevelRenderer.compileSections 的 isDirty 判断，邻居不全就不编译
 * SectionOcclusionGraph 两处，邻居不全就进 chunksWaitingForNeighbors，不进遮挡队列
 * 视距边缘的中心 chunk 先到、邻居还在路上，于是该 section 既不进可见队列也不被编译，
 * 表现是玩家周围一圈不渲染，重进后因为邻居一起到达才恢复。
 *
 * 本 port 所有 chunk 直通 FULL，邻居的缺失永远只是包时序问题，不是数据缺失，所以这道
 * 邻居保护在这里没有意义。行为等价于旧仓库的 RenderSectionMixin。
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class SectionRenderDispatcher$RenderSectionMixin {

    @Overwrite
    public boolean hasAllNeighbors() {
        return true;
    }
}
