package com.inf.farlands.client.gui;

/**
 * 世界摘要携带的兼容性标记，由 {@code LevelSummaryMixin} 注入到 {@code LevelSummary}。
 *
 * <p>存在的理由只有一条：选择世界页要按「该世界的 systems.dat 能不能读回」改变描述行、缩略图与三个
 * 按钮，而 {@code LevelSummary} 上没有承载这个状态的字段。用接口而不是反射，是因为读点在做判据的那
 * 一侧，反射每帧一次的代价与类型安全都不划算。
 *
 * <p>只在客户端加载：注入它的 mixin 在 client 配置里，专用服务器上的 {@code LevelSummary} 不带本接口，
 * 那边的兼容性判据仍只看版本。
 */
public interface SystemsCompat {

    /** 该世界的 systems.dat 存在但读不出来。文件不存在不算。 */
    boolean systemsIncompatible();
}
