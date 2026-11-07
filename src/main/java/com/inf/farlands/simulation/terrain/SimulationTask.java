package com.inf.farlands.simulation.terrain;

import com.inf.farlands.simulation.model.SimulationProducer;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

/**
 * 一段的生产任务，跑在 {@code farlands-sim} 上。
 *
 * <p>
 * 只做纯计算：写自己那一段的容器，不碰段表、不升档、不碰网络。收尾在主线程由提交方给的回调做，因为段表的
 * 条件摘除与档位推进都要求主线程。
 *
 * <p>
 * 输入是提交那一刻捕获的引用。若期间该段被释放、同坐标又建出了新的一代，回调里的条件判据会把它认出来并
 * 丢弃本次结果，本类因此不需要知道段表的存在。
 *
 * <p>
 * 写容器要显式走五参 {@code setBlockState} 的 {@code false} 一档，或直接写容器的其它无锁入口。理由与地形侧
 * 填段相同：带检测的那一档会取容器自己的线程检测器，而主线程的打包与落盘编码也要取它。
 */
public final class SimulationTask implements Runnable {

    private final SimulationSectionKey key;

    private final SimulationSectionData data;

    private final SimulationProducer producer;

    public SimulationTask(SimulationSectionKey key, SimulationSectionData data, SimulationProducer producer) {
        this.key = key;
        this.data = data;
        this.producer = producer;
    }

    public SimulationSectionKey key() {
        return this.key;
    }

    public SimulationSectionData data() {
        return this.data;
    }

    @Override
    public void run() {
        this.producer.produce(this.key, this.data);
    }
}
