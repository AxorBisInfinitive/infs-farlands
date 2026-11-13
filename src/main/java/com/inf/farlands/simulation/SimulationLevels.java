package com.inf.farlands.simulation;

import com.inf.farlands.simulation.int64.SimulationNoiseSystem;

/**
 * 模拟系统实例的取值入口，由 {@code ServerLevel} 注入，与 farlands 的 {@code SystemsHolder} 同职责。
 *
 * <p>
 * 实例在 level 构造末尾按该维度的落盘选择反射新建一次，之后复用；字段写在服务端线程，池线程上的填充任务只读。
 * 取不到即说明构造没走完，不许在这里惰性新建：惰性会让解析落到首个 tick，那时世界已初始化，缺档会被判成
 * 「旧世界缺档案」而抛。
 */
public interface SimulationLevels {

    /** 该 level 的模拟系统。 */
    SimulationNoiseSystem simulationSystem();
}
