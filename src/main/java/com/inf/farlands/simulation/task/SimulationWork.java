package com.inf.farlands.simulation.task;

import com.inf.farlands.simulation.model.SimulationSectionKey;

/**
 * 一次池上生产：一个段的一份工作。
 *
 * <p>
 * 池只认两件事，跑什么由实现决定。{@code key} 只供失败日志定位，池不改段表、不升档。
 */
public interface SimulationWork extends Runnable {

    /** 本次工作所属的段。 */
    SimulationSectionKey key();
}
