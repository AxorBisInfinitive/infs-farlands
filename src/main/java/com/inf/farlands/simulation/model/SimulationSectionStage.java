package com.inf.farlands.simulation.model;

/**
 * 模拟段的阶段，两档单值，比较用 {@code >=}。
 *
 * <p>
 * 0 UNPROCESSED 段已建，内容未定；1 PROCESSED 内容已定，此后不再变。
 *
 * <p>
 * 两条门要读它：只有 {@code PROCESSED} 的段可以下发，也只有它可以落盘。这与地形侧
 * {@code SectionStage.isTerminal} 是同一形状，那边一条门同时管落盘与下发。
 *
 * <p>
 * 档位单调不减，推进走 {@code SimulationSectionData.advanceTo}。
 *
 * <p>
 * 预留入口，当前无调用点：下发门与落盘门在后续切片里接。
 */
public final class SimulationSectionStage {

    /** 段已建，内容未定。 */
    public static final int UNPROCESSED = 0;

    /** 内容已定，此后不再变。 */
    public static final int PROCESSED = 1;

    private SimulationSectionStage() {
    }

    /** 是否已到终态，即内容可下发、可落盘。 */
    public static boolean isTerminal(int stage) {
        return stage >= PROCESSED;
    }
}
