package com.inf.farlands.simulation.model;

/**
 * 模拟段的生产者。一段只在被创建时跑一次。
 *
 * <p>
 * 与客户端原来的层定义同形：段被创建即产出一份内容，对应原版「chunk 被加载即生成一次」的节奏。写入方只有
 * 服务端主线程，生产者因此不需要考虑并发。
 */
public interface SimulationProducer {

    /** 把 {@code key} 那一段的内容写进 {@code data}。段容器已经建好，块与群系默认是空的。 */
    void produce(SimulationSectionKey key, SimulationSectionData data);
}
