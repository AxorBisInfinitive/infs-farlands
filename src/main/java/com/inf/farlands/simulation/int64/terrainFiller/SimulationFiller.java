package com.inf.farlands.simulation.int64.terrainFiller;

import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

import net.minecraft.server.level.ServerLevel;

/**
 * 段填充器接口，对应 terrain 的 {@code TerrainFiller}。
 *
 * <p>形参比那边少两个：terrain 的填充单位是 chunk 的段区间，仿真的单位就是一段，区间收敛成段键本身。
 *
 * <p>{@code level} 保留是为了将来按 level 取系统或取设置，当前实现只用它做接待者。
 */
public interface SimulationFiller {

    void fill(ServerLevel level, SimulationSectionKey key, SimulationSectionData data);
}
