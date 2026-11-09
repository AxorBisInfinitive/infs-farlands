package com.inf.farlands.simulation.model;

/**
 * 模拟段的阶段，三档单值，比较用 {@code >=}。
 *
 * <p>
 * 0 UNPROCESSED 段已建，内容未定；1 NOISE 生产在途，内容正在被池线程写；2 LIGHTED 内容已定、
 * 统一光照已写，此后不再变。
 *
 * <p>
 * 两条门要读它：只有 {@code LIGHTED} 的段可以下发，也只有它可以落盘。这与地形侧
 * {@code SectionStage.isTerminal} 是同一形状，那边一条门同时管落盘与下发。
 *
 * <p>
 * {@code LIGHTED} 蕴含「统一光照已写」，即天光 15、方块光 0。写光必须早于升档：读侧两处都以
 * {@code isTerminal} 为前置，晚于升档就会漏出「已终态而光是空的」的窗口。
 *
 * <p>
 * 档位单调不减，推进走 {@code SimulationSectionData.advanceTo}。生产失败不升档，段停在
 * {@code NOISE}，等它离开视距时被丢弃。
 */
public final class SimulationSectionStage {

    /** 段已建，内容未定。 */
    public static final int UNPROCESSED = 0;

    /** 生产在途，内容正在被池线程写。 */
    public static final int NOISE = 1;

    /** 内容已定、统一光照已写，此后不再变。 */
    public static final int LIGHTED = 2;

    private SimulationSectionStage() {
    }

    /** 是否已到终态，即内容可下发、可落盘。 */
    public static boolean isTerminal(int stage) {
        return stage >= LIGHTED;
    }
}
