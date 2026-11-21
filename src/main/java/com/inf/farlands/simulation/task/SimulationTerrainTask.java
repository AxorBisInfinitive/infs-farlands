package com.inf.farlands.simulation.task;

import com.inf.farlands.simulation.int1024.terrainFiller.SimulationFiller;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * 一段的地形填充任务，跑在 {@code farlands-sim} 上。地形族的两种范式共用它。
 *
 * <p>
 * 只做纯计算：把段与邻域快照交给填充器，填充器写自己那一段的容器，不碰段表、不升档、不碰网络。收尾在主线程由
 * 提交方给的回调做，因为段表的条件摘除与档位推进都要求主线程。
 *
 * <p>
 * 邻域快照由主线程在提交前拍好：{@code SimulationStore} 的内层表是普通 map，池线程读它不合法。快照只在逐段范式
 * 用得上，密度链那一支不看它。
 *
 * <p>
 * 输入是提交那一刻捕获的引用。若期间该段被释放、同坐标又建出了新的一代，回调里的条件判据会把它认出来并
 * 丢弃本次结果，本类因此不需要知道段表的存在。
 *
 * <p>
 * 写容器要显式走五参 {@code setBlockState} 的 {@code false} 一档，或直接写容器的其它无锁入口。理由与地形侧
 * 填段相同：带检测的那一档会取容器自己的线程检测器，而主线程的打包与落盘编码也要取它。
 */
public final class SimulationTerrainTask implements SimulationWork {

    private final ServerLevel level;

    private final SimulationSectionKey key;

    private final SimulationSectionData data;

    private final PalettedContainerRO<Holder<Biome>>[] biomes;

    private final SimulationFiller filler;

    public SimulationTerrainTask(ServerLevel level, SimulationSectionKey key, SimulationSectionData data,
            PalettedContainerRO<Holder<Biome>>[] biomes, SimulationFiller filler) {
        this.level = level;
        this.key = key;
        this.data = data;
        this.biomes = biomes;
        this.filler = filler;
    }

    @Override
    public SimulationSectionKey key() {
        return this.key;
    }

    @Override
    public void run() {
        this.filler.fill(this.level, this.key, this.data, this.biomes);
    }
}
