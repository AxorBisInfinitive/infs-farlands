package com.inf.farlands.simulation.task;

import com.inf.farlands.simulation.SimulationLevels;
import com.inf.farlands.simulation.biomeFiller.SimulationBiomeFiller;
import com.inf.farlands.simulation.int1024.SimulationBiomeSystem;
import com.inf.farlands.simulation.int1024.SimulationRouting;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

import net.minecraft.server.level.ServerLevel;

/**
 * 一段的群系填充任务，跑在 {@code farlands-sim} 上。
 *
 * <p>
 * 与 {@link SimulationTerrainTask} 同一个规矩：只做纯计算，写自己那一段的容器，不碰段表、不升档、不碰网络。收尾
 * 由提交方的回调在主线程做，因为档位推进与段表的读写都要求主线程。
 *
 * <p>
 * 系统与路由在 {@link #run()} 里现取：任务瞬态，构造时不该快照任何会被换掉的东西；实例随 level 走，本类只借用。
 *
 * <p>
 * 写入走容器的 {@code getAndSetUnchecked}，那一档不取容器自己的线程检测器，与 vanilla 的
 * {@code fillBiomesFromNoise} 同一档。
 */
public final class SimulationBiomeTask implements SimulationWork {

    private final ServerLevel level;

    private final SimulationSectionKey key;

    private final SimulationSectionData data;

    public SimulationBiomeTask(ServerLevel level, SimulationSectionKey key, SimulationSectionData data) {
        this.level = level;
        this.key = key;
        this.data = data;
    }

    @Override
    public SimulationSectionKey key() {
        return this.key;
    }

    @Override
    public void run() {
        SimulationBiomeSystem system = ((SimulationLevels) this.level).simulationBiomeSystem();
        SimulationBiomeFiller.fill(this.level, this.key, this.data, system, SimulationRouting.router(this.level));
    }
}
