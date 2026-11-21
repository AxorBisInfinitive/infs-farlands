package com.inf.farlands.simulation.model;

/**
 * 模拟段的阶段，四档单值，比较用 {@code >=}。
 *
 * <p>
 * 0 UNPROCESSED 段已建，什么都没有；1 BIOME 4x4x4 群系网格已写，方块未定；2 NOISE 方块生产在途或已写完，
 * 光照未定；3 LIGHTED 内容已定、统一光照已写，此后不再变。
 *
 * <p>
 * 群系与方块分成两档，档序是 BIOME 在前。理由是依赖方向：NOISE 可以依赖 BIOME，逐段范式要读群系的格值，反过来
 * 没有这条。成本上的差别是次要的，群系单段便宜，而方块链失败时群系的成果不该一起丢。
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
 * 档位单调不减，推进走 {@code SimulationSectionData.advanceTo}。失败不升档：群系失败停在 {@code UNPROCESSED}，
 * 方块失败停在 {@code NOISE}，两种都在段离开视距时被丢弃。
 *
 * <p>
 * 档位是持久化值，随每段一起写进 ssa 条目。改这张表等于让旧档失效，而这条失效是安全的、也不需要版本字段：落盘
 * 只发生在终态，所以盘上的值只有旧 {@code LIGHTED} 一个，重排之后它落在非终态上，读回时按未命中重生成。这条推理
 * 依赖「只有终态段落盘」，那道守卫在 {@code SimulationLifecycle.persistSection} 的入口。
 */
public final class SimulationSectionStage {

    /** 段已建，什么都没有。 */
    public static final int UNPROCESSED = 0;

    /** 群系网格已写，方块未定。 */
    public static final int BIOME = 1;

    /** 方块生产在途或已写完，光照未定。 */
    public static final int NOISE = 2;

    /** 内容已定、统一光照已写，此后不再变。 */
    public static final int LIGHTED = 3;

    private SimulationSectionStage() {
    }

    /** 是否已到终态，即内容可下发、可落盘。 */
    public static boolean isTerminal(int stage) {
        return stage >= LIGHTED;
    }
}
