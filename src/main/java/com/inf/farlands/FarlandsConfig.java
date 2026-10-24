package com.inf.farlands;

import java.util.Map;

import com.inf.farlands.util.config.Config;
import com.inf.farlands.util.config.ConfigEntry;

/**
 * 本 mod 的全部配置项，分两份文件：主配置与调试配置。逐项 builder 声明，见 {@link Config}。
 *
 * <p>
 * 值域一律由 {@code .range(...)} 声明，越界在读盘期抛异常，不再有"就地钳制"：
 * 钳制会留下"磁盘值越界、内存值被改过"的两份状态，越界即抛则只有一份。
 *
 * <p>
 * 两个线程数项的值可以写 {@code "auto"}，由 {@link #autoThreads()} 在声明处提供取值器。
 */
public class FarlandsConfig {

    /** {@code "auto"} 的取值器：CPU 逻辑线程数一半，最小 1。 */
    private static int autoThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    /** 主配置文件 config/infs-farlands.json。 */
    private static final Config CONFIG = new Config("infs-farlands",
            Map.of("en_us", "Inf's Farlands configuration", "zh_cn", "Inf's Farlands 配置文件"));

    /** 调试配置文件 config/infs-farlands-debug.json。 */
    private static final Config DEBUG = new Config("infs-farlands-debug",
            Map.of("en_us", "Inf's Farlands debug configuration",
                    "zh_cn", "Inf's Farlands 调试配置"));

    public static final ConfigEntry<Integer> BORDER_ABSOLUTE_MAX = CONFIG.setInt("borderAbsoluteMax")
            .comment("en_us", "Maximum world border size")
            .comment("zh_cn", "世界边界最大尺寸")
            .min(1)
            .define(FarlandsConstant.MAX_BLOCK)
            .build();
    public static final int borderAbsoluteMax;

    public static final ConfigEntry<Boolean> OUTSIDE = CONFIG.setBoolean("outside")
            .comment("en_us", "Allow entities to leave the world, disabling the six-sided boundary")
            .comment("zh_cn", "允许实体离开世界，关闭六面边界")
            .define(false)
            .build();
    public static final boolean outside;

    public static final ConfigEntry<Integer> WORLD_GEN_MIN_Y = CONFIG.setInt("worldGenMinY")
            .comment("en_us", "Minimum absolute Y for world generation")
            .comment("zh_cn", "世界生成的绝对最小 Y")
            .define(~FarlandsConstant.MAX_BLOCK)
            .build();
    public static final int worldGenMinY;

    public static final ConfigEntry<Integer> WORLD_GEN_MAX_Y = CONFIG.setInt("worldGenMaxY")
            .comment("en_us", "Maximum absolute Y for world generation")
            .comment("zh_cn", "世界生成的绝对最大 Y")
            .define(FarlandsConstant.MAX_BLOCK)
            .build();
    public static final int worldGenMaxY;

    // 通用循环上限

    public static final ConfigEntry<Integer> MAX_CAP_ITER = CONFIG.setInt("maxCapIter")
            .comment("en_us", "Iteration cap for Y-axis loops")
            .comment("zh_cn", "Y 轴循环的迭代上限")
            .range(1, 65536)
            .define(512)
            .build();
    public static final int maxCapIter;

    public static final ConfigEntry<Integer> VERTICAL_SIMULATION_DISTANCE = CONFIG
            .setInt("verticalSimulationDistance")
            .comment("en_us", "Radius of the vertical simulation distance")
            .comment("zh_cn", "垂直模拟距离的半径")
            .range(1, 64)
            .define(17)
            .build();
    public static final int verticalSimulationDistance;

    public static final ConfigEntry<Integer> SECTION_CLEANUP_MARGIN = CONFIG.setInt("sectionCleanupMargin")
            .comment("en_us",
                    "Sections outside the window plus this margin are released or cleaned up")
            .comment("zh_cn", "窗口外加上该余量的 section 属于释放或清理范围")
            .range(0, 64)
            .define(8)
            .build();
    public static final int sectionCleanupMargin;

    public static final ConfigEntry<Integer> FSA_CACHE_LIMIT = CONFIG.setInt("fsaCacheLimit")
            .comment("en_us", "Maximum number of open fsa files kept in cache")
            .comment("zh_cn", "打开的 fsa 文件缓存上限")
            .range(16, 1024)
            .define(128)
            .build();
    public static final int fsaCacheLimit;

    public static final ConfigEntry<Long> FSA_PERSIST_INTERVAL = CONFIG.setLong("fsaPersistInterval")
            .comment("en_us", "Interval in ticks between periodic persistence of dirty sections")
            .comment("zh_cn", "定期持久化脏 section 的间隔，单位 tick")
            .range(100L, 120000L)
            .define(6000L)
            .build();
    public static final long fsaPersistInterval;

    // 地形管线

    public static final ConfigEntry<Integer> MAX_GEN_TASKS_PER_TICK = CONFIG.setInt("maxGenTasksPerTick")
            .comment("en_us", "Max terrain-pipeline gen tasks consumed per wake")
            .comment("zh_cn", "地形管线每次唤醒消费的生成任务上限")
            .range(1, 65536)
            .define(2000)
            .build();
    public static final int maxGenTasksPerTick;

    public static final ConfigEntry<Integer> GEN_WORKER_THREADS = CONFIG.setInt("genWorkerThreads")
            .comment("en_us",
                    "Terrain generation worker threads")
            .comment("zh_cn",
                    "地形生成线程数")
            .range(1, 64)
            .keyword("auto", FarlandsConfig::autoThreads)
            .commentKeyword("auto", "en_us", "Half of the CPU logical processors, min 1")
            .commentKeyword("auto", "zh_cn", "CPU 逻辑线程数的一半，最小值为 1")
            .define("auto")
            .build();
    public static final int genWorkerThreads;

    // 光照

    public static final ConfigEntry<Integer> PARALLEL_LIGHT_THREADS = CONFIG.setInt("parallelLightThreads")
            .comment("en_us",
                    "Server-side light propagation threads")
            .comment("zh_cn",
                    "服务端光照传播线程数")
            .range(1, 64)
            .keyword("auto", FarlandsConfig::autoThreads)
            .commentKeyword("auto", "en_us", "Half of the CPU logical processors, min 1")
            .commentKeyword("auto", "zh_cn", "CPU 逻辑线程数的一半，最小值为 1")
            .define("auto")
            .build();
    public static final int parallelLightThreads;

    // 本值是光照任务的放行速率，不是并行度：并行度由 parallelLightThreads 决定，本值是每 50 毫秒
    // 最多放行多少个 chunk 的光照任务。它在加载关键路径上：chunk 光照在途时 isChunkBusy 为真，
    // ChunkReadiness 因此不补 FULL、WindowSendState 因此不发段包，所以调小会让地形下发与存档退出
    // 一起退化。32 是按「飞行时地形跟得上」定的。
    public static final ConfigEntry<Integer> MAX_LIGHT_TASKS_PER_TICK = CONFIG.setInt("maxLightTasksPerTick")
            .comment("en_us",
                    "Light tasks admitted per 50ms wake, on the chunk delivery critical path")
            .comment("zh_cn",
                    "每 50 毫秒放行的光照传播任务上限，压在 chunk 下发关键路径上")
            .range(1, 64)
            .define(32)
            .build();
    public static final int maxLightTasksPerTick;

    public static final ConfigEntry<Integer> SECTION_SEND_BYTES_PER_TICK = CONFIG.setInt("sectionSendBytesPerTick")
            .comment("en_us",
                    "Max window-slide section bytes sent per tick per player")
            .comment("zh_cn",
                    "每 tick 每玩家发送的窗口滑动 section 最大字节数")
            .range(1024, 1048576)
            .define(131072)
            .build();
    public static final int sectionSendBytesPerTick;

    // 调试配置：诊断日志的启用开关，改文件后需重启生效。

    public static final ConfigEntry<Boolean> LOG_BLOCK_LOOKUP_SWAP = DEBUG.setBoolean("logBlockLookupSwap")
            .comment("en_us", "Log the BlockUtil lookup generation swap every 200 ticks")
            .comment("zh_cn", "每 200 tick 打印 BlockUtil.lookup 换代")
            .define(false)
            .build();
    public static final boolean logBlockLookupSwap;

    public static final ConfigEntry<Boolean> LOG_SECTION_LOOKUP_TRIM = DEBUG.setBoolean("logSectionLookupTrim")
            .comment("en_us", "Log the SectionUtil lookup trim every 200 ticks")
            .comment("zh_cn", "每 200 tick 打印 SectionUtil.lookup 回收")
            .define(false)
            .build();
    public static final boolean logSectionLookupTrim;

    public static final ConfigEntry<Boolean> LOG_AQUIFER_LOOKUP_TRIM = DEBUG
            .setBoolean("logAquiferLookupTrim")
            .comment("en_us", "Log the AquiferUtil lookup trim every 200 ticks")
            .comment("zh_cn", "每 200 tick 打印 AquiferUtil.lookup 回收")
            .define(false)
            .build();
    public static final boolean logAquiferLookupTrim;

    public static final ConfigEntry<Boolean> LOG_STAGE_METRICS = DEBUG.setBoolean("logStageMetrics")
            .comment("en_us", "Log the FLSTAGE throughput reading every 200 ticks")
            .comment("zh_cn", "每 200 tick 打印 FLSTAGE 就绪吞吐读数")
            .define(false)
            .build();
    public static final boolean logStageMetrics;

    public static final ConfigEntry<Boolean> LOG_INVERTED_BOUNDING_BOX = DEBUG
            .setBoolean("logInvertedBoundingBox")
            .comment("en_us", "Log inverted BoundingBox constructions, one line per call path per second")
            .comment("zh_cn", "每秒至多打印一次反序的 BoundingBox 构造，同一调用路径限流")
            .define(true)
            .build();
    public static final boolean logInvertedBoundingBox;

    public static final ConfigEntry<Boolean> LOG_INVERTED_BOUNDING_BOX_MOVE = DEBUG
            .setBoolean("logInvertedBoundingBoxMove")
            .comment("en_us", "Log BoundingBox moves that invert the bounds, one line per call path per second")
            .comment("zh_cn", "每秒至多打印一次使 BoundingBox 反序的 move，同一调用路径限流")
            .define(true)
            .build();
    public static final boolean logInvertedBoundingBoxMove;

    static {
        // 两份文件各读一次、解析一次、约束一次。本类初始化由首个引用它的类触发。
        CONFIG.init();
        borderAbsoluteMax = BORDER_ABSOLUTE_MAX.get();
        outside = OUTSIDE.get();
        worldGenMinY = WORLD_GEN_MIN_Y.get();
        worldGenMaxY = WORLD_GEN_MAX_Y.get();
        maxCapIter = MAX_CAP_ITER.get();
        verticalSimulationDistance = VERTICAL_SIMULATION_DISTANCE.get();
        sectionCleanupMargin = SECTION_CLEANUP_MARGIN.get();
        fsaCacheLimit = FSA_CACHE_LIMIT.get();
        fsaPersistInterval = FSA_PERSIST_INTERVAL.get();
        maxGenTasksPerTick = MAX_GEN_TASKS_PER_TICK.get();
        genWorkerThreads = GEN_WORKER_THREADS.get();
        parallelLightThreads = PARALLEL_LIGHT_THREADS.get();
        maxLightTasksPerTick = MAX_LIGHT_TASKS_PER_TICK.get();
        sectionSendBytesPerTick = SECTION_SEND_BYTES_PER_TICK.get();

        DEBUG.init();
        logBlockLookupSwap = LOG_BLOCK_LOOKUP_SWAP.get();
        logSectionLookupTrim = LOG_SECTION_LOOKUP_TRIM.get();
        logAquiferLookupTrim = LOG_AQUIFER_LOOKUP_TRIM.get();
        logStageMetrics = LOG_STAGE_METRICS.get();
        logInvertedBoundingBox = LOG_INVERTED_BOUNDING_BOX.get();
        logInvertedBoundingBoxMove = LOG_INVERTED_BOUNDING_BOX_MOVE.get();
    }
}
