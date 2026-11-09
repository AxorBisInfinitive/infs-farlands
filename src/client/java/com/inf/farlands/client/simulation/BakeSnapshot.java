package com.inf.farlands.client.simulation;

import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.biome.Biome;

/**
 * 一次烘制的输入，全部在主线程取好，工作线程只读。
 *
 * <p>
 * 客户端物件必须在这里定住：{@link BlockColors}、{@link BlockStateModelSet}、{@link CardinalLighting}、环境光遮蔽、
 * 群系混合半径与 {@code cutoutLeaves}。它们在资源重载与设置界面里会被换成新实例，工作线程自己去取就会读到一半的
 * 替换。
 *
 * <p>
 * 内容取「已发布的那一份」而不是段本身：{@code content} 与 27 个邻域内容都是 {@link SimulationSection.Content}，
 * 发布之后不再被改，所以工作线程读到的是一代完整内容，不会读到写了一半的数组或换了一半的光照层。主线程在收包、
 * 卸载与单格写入时换新的一份，本快照仍指向原来那份。
 *
 * <p>
 * {@code owner} 只为 tint 缓存服务：那张缓存挂在段上、按格存，与具体哪一代内容无关，主线程在群系变化时统一清空。
 * 除此之外工作线程不读 {@code owner} 的任何字段。
 *
 * <p>
 * {@code missing} 是这份邻域里缺席的那些位的掩码，位序与 {@code neighbors} 的下标同一套。它随烘制结果写回段上，
 * 供邻段到达时判定「这一段当初是不是缺了它」。
 *
 * <p>
 * {@code sorting} 是透明层排四边形用的相机基准，按「相机相对本段原点」构造，与地形在编译期的做法同源。它只反映
 * 提交那一刻，相机随后移动不会让已烘的段重排。
 *
 * <p>
 * {@code revision} 与 {@code generation} 都在主线程写作与比较，工作线程只从快照里读，靠提交队列的发布关系可见。
 * 前者判内容是否在烘制期间变过，后者判关卡是否换过。
 */
record BakeSnapshot(
        Simulation.SectionKey key,
        int generation,
        int revision,
        SimulationSection owner,
        SimulationSection.Content content,
        SimulationSection.Content[] neighbors,
        int missing,
        Holder<Biome> defaultBiome,
        BlockColors blockColors,
        BlockStateModelSet models,
        CardinalLighting cardinalLighting,
        boolean ambientOcclusion,
        int biomeBlendRadius,
        boolean cutoutLeaves,
        VertexSorting sorting) {
}
