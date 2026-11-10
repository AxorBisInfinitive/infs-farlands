package com.inf.farlands.simulation.model;

import java.util.Map;

import com.inf.config.util.config.Config;
import com.inf.config.util.config.ConfigEntry;

/**
 * 服务端模拟层的配置，独立一份文件 {@code config/infs-farlands-simulation.json}。
 *
 * <p>
 * 声明风格与 {@code FarlandsConfig} 一致：逐项 builder，值域一律由 {@code .range(...)} 或 {@code .min(...)} 与
 * {@code .max(...)} 声明，越界在读盘期抛异常，不在读盘后就地钳制。每个条目同时留一个同名小写静态字段，由本类的
 * 静态块在 {@link Config#init()} 之后赋值，消费方读那个字段而不是条目自己的 {@code get()}。
 *
 * <p>
 * 一份文件一个 {@link Config} 实例，{@link Config#init()} 是唯一读盘点，不支持热重载，结构性的理由见
 * {@code Config} 的类注释。条目按需加在这里，加条目时同时补静态块里的赋值。
 */
public class SimulationConfig {

    /** 主配置文件 config/infs-farlands-simulation.json。 */
    private static final Config CONFIG = new Config("infs-farlands-simulation",
            Map.of("en_us", "Inf's Farlands simulation configuration",
                    "zh_cn", "Inf's Farlands 模拟配置"));

    /** 位置含 NaN 时的噪点覆盖层开关。 */
    public static final ConfigEntry<Boolean> NAN_STATIC_OVERLAY = CONFIG.setBoolean("nanStaticOverlay")
            .comment("en_us", "Draw the static noise overlay while any position axis is NaN")
            .comment("zh_cn", "位置任一轴为 NaN 时是否显示噪点覆盖层")
            .define(true)
            .build();
    public static final boolean nanStaticOverlay;

    /** 打开的 ssa 文件缓存上限。 */
    public static final ConfigEntry<Integer> SSA_CACHE_LIMIT = CONFIG.setInt("ssaCacheLimit")
            .comment("en_us", "Maximum number of open ssa files kept in cache")
            .comment("zh_cn", "打开的 ssa 文件缓存上限")
            .range(16, 1024)
            .define(128)
            .build();
    public static final int ssaCacheLimit;

    /** 定期持久化脏模拟段的间隔，单位 tick。 */
    public static final ConfigEntry<Long> SSA_PERSIST_INTERVAL = CONFIG.setLong("ssaPersistInterval")
            .comment("en_us", "Interval in ticks between periodic persistence of dirty simulation sections")
            .comment("zh_cn", "定期持久化脏模拟段的间隔，单位 tick")
            .range(100L, 120000L)
            .define(6000L)
            .build();
    public static final long ssaPersistInterval;

    /** 客户端烘焙模拟段的线程数。 */
    public static final ConfigEntry<Integer> MESH_WORKER_THREADS = CONFIG.setInt("meshWorkerThreads")
            .comment("en_us", "Client-side worker threads that mesh simulation sections")
            .comment("zh_cn", "SimulationSection 客户端烘焙线程数")
            .range(1, 64)
            .keyword("auto", SimulationConfig::autoThreads)
            .commentKeyword("auto", "en_us", "Half of the CPU logical processors, min 1")
            .commentKeyword("auto", "zh_cn", "CPU 逻辑线程数的一半，最小值为 1")
            .define("auto")
            .build();
    public static final int meshWorkerThreads;

    /** {@code "auto"} 的取值器：CPU 逻辑线程数一半，最小 1。 */
    private static int autoThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    static {
        CONFIG.init();
        nanStaticOverlay = NAN_STATIC_OVERLAY.get();
        ssaCacheLimit = SSA_CACHE_LIMIT.get();
        ssaPersistInterval = SSA_PERSIST_INTERVAL.get();
        meshWorkerThreads = MESH_WORKER_THREADS.get();
    }
}
