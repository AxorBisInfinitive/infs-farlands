package com.inf.farlands.client.gui;

/**
 * 世界摘要携带的两层兼容性标记，由 {@code LevelSummaryMixin} 注入到 {@code LevelSummary}。
 *
 * <p>
 * 存在的理由只有一条：选择世界页要按「该世界的两份档案能不能读回」改变描述行、缩略图与三个按钮，而
 * {@code LevelSummary} 上没有承载这个状态的字段。用接口而不是反射，是因为读点在做判据的那一侧，反射每帧
 * 一次的代价与类型安全都不划算。
 *
 * <p>
 * 两层各一法：档案分属 farlands 与模拟层，坏法各异，表现却共用同一个判据形状。只在客户端加载：注入它的
 * mixin 在 client 配置里，专用服务器上的 {@code LevelSummary} 不带本接口，那边的兼容性判据仍只看版本。
 */
public interface SelectionCompat {

    /** 该世界的 systems.dat 存在但读不出来。文件不存在不算。 */
    boolean systemsIncompatible();

    /** 该世界的 simulation.dat 存在但读不出来。文件不存在不算。 */
    boolean simulationIncompatible();
}
