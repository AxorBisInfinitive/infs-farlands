package com.inf.farlands.client.simulation;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.biome.Biome;

/**
 * 一次烘制的输入，全部在主线程取好，工作线程只读。
 *
 * <p>
 * 五个客户端物件必须在这里定住：{@link BlockColors}、{@link BlockStateModelSet}、{@link CardinalLighting}、
 * 环境光遮蔽开关与群系混合半径。它们在资源重载与设置界面里会被换成新实例，工作线程自己去取就会读到一半的替换。
 *
 * <p>
 * {@code occupied} 是拷贝。它在原数组上原地增删，共享引用会让工作线程读到写了一半的计数与下标。段容器本身取引用，
 * 因为 {@code applyContainer} 整体换 {@code slots}、整体换光照层，在途任务拿到的是一份冻结的旧内容。
 *
 * <p>
 * {@code neighbors} 是主线程解析好的 27 格邻域。工作线程不查 {@code Simulation.sections}，那是普通 HashMap，
 * 主线程在收包与卸载时增删它。
 *
 * <p>
 * {@code revision} 与 {@code generation} 都在主线程写作与比较，工作线程只从快照里读，靠提交队列的发布关系可见。
 * 前者判内容是否在烘制期间变过，后者判关卡是否换过。
 */
record BakeSnapshot(
        Simulation.SectionKey key,
        int generation,
        int revision,
        SimulationSection section,
        int[] occupied,
        int occupiedCount,
        SimulationSection[] neighbors,
        Holder<Biome> defaultBiome,
        BlockColors blockColors,
        BlockStateModelSet models,
        CardinalLighting cardinalLighting,
        boolean ambientOcclusion,
        int biomeBlendRadius) {
}
