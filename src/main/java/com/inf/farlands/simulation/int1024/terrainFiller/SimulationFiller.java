package com.inf.farlands.simulation.int1024.terrainFiller;

import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * 段填充器接口，对应 terrain 的 {@code TerrainFiller}。
 *
 * <p>形参比那边少两个：terrain 的填充单位是 chunk 的段区间，仿真的单位就是一段，区间收敛成段键本身。
 *
 * <p>{@code biomes} 是主线程拍好的 27 格邻域群系快照，只有逐段范式用得上；密度链那一支不看它。它随段一起传，
 * 是因为拍快照必须发生在主线程，而填充跑在池上。
 *
 * <p>{@code level} 保留是为了将来按 level 取系统或取设置，当前实现只用它做接待者。
 */
public interface SimulationFiller {

    /** 填一段。{@code biomes} 是邻域群系快照，槽位见 {@code SimulationSectionContext.snapshot}。 */
    void fill(ServerLevel level, SimulationSectionKey key, SimulationSectionData data,
            PalettedContainerRO<Holder<Biome>>[] biomes);
}
