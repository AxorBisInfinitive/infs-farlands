package com.inf.farlands.simulation;

import com.inf.farlands.simulation.int1024.SimulationBiomeSystem;
import com.inf.farlands.simulation.int1024.SimulationTerrainSystem;

/**
 * 模拟系统实例的取值入口，由 {@code ServerLevel} 注入，与 farlands 的 {@code SystemsHolder} 同职责。
 *
 * <p>
 * 地形那一族返回的是基接口 {@link SimulationTerrainSystem}，与 terrain 层 {@code LevelSystems.terrainSystem()}
 * 返回 {@code TerrainSystem} 同形：范式分派由填充器在那个基类型上做，取用点不认识具体是哪一个范式。群系族与它
 * 平级，单独一个。
 *
 * <p>
 * 两族各一个实例。它们在 level 构造末尾按该维度的落盘选择各反射新建一次，之后复用；字段写在服务端线程，池线程
 * 上的填充任务只读。取不到即说明构造没走完，不许在这里惰性新建：惰性会让解析落到首个 tick，那时世界已初始化，
 * 缺档会被判成「旧世界缺档案」而抛。
 */
public interface SimulationLevels {

    /** 该 level 的地形系统。基接口，具体范式由填充器判。 */
    SimulationTerrainSystem simulationTerrainSystem();

    /** 该 level 的群系系统。 */
    SimulationBiomeSystem simulationBiomeSystem();
}
