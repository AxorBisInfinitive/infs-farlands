package com.inf.farlands.client.simulation;

/**
 * {@code Minecraft} 实例上的模拟方块层入口，由 {@code simulation.MinecraftMixin} 注入实现。
 *
 * <p>
 * 与仓库既有的接口注入同形，例如 {@code WindowedChunk} 挂 {@code ChunkAccess}。
 */
public interface SimulationHolder {

    Simulation simulation();
}
