package com.inf.farlands.simulation;

import java.util.Map;

import com.inf.farlands.util.config.Config;
import com.inf.farlands.util.config.ConfigEntry;

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

    static {
        CONFIG.init();
        nanStaticOverlay = NAN_STATIC_OVERLAY.get();
    }
}
