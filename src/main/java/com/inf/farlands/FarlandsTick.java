package com.inf.farlands;

import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.pipeline.NeighborhoodTickets;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173.Beta173NoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla.VanillaNoiseSystem;
import com.inf.farlands.util.maps.AquiferUtil;
import com.inf.farlands.util.maps.BlockUtil;
import com.inf.farlands.util.maps.SectionUtil;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.network.SystemsSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.PlayerSnapshot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class FarlandsTick {
    private static final int INTERVAL = 200;
    private static volatile int now = 0;

    public static int getNow() {
        return now;
    }

    /**
     * 上一轮 fsa 清理是否因预算耗尽而没做完。窗口静止时窗口并集不再变化，只按 windowChanged 触发会让
     * 没清完的积压一直留着，所以未清完就下 tick 续跑。预算耗尽只由「已点亮、脏、待入队」的段超出上限
     * 引起，释放那一支不扣预算，因此清完之后本标记恒假，不会常驻扫描。
     */
    private static boolean cleanupOpen;

    /**
     * 单一时钟的写入点：服务端 tick 与分离 JVM 的客户端 tick 都从这里推进，侧信道打戳读的
     * 就是这个值。
     */
    public static void setNow(int tickCount) {
        now = tickCount;
    }

    private static void swapBlockLookup(MinecraftServer server, int tickCount) {
        // size 必须在 swap 前取：swap 之后读到的是新的空表，恒为 0。
        int size = BlockUtil.size();
        if (FarlandsConfig.logBlockLookupSwap) {
            InfsFarlands.LOGGER.info("Swapping BlockUtil.lookup, size: {}", size);
        }
        BlockUtil.swap();
        if (size > 0) {
            awardIntBlockPos(server);
        }
    }

    /**
     * 这一轮在 swap 之前至少注册过一个坐标时，给所有在线玩家授予成就。
     * 对应进度的 criterion 是 minecraft:impossible，永不自动满足，只能由本方法授予。
     * award 对已解锁的玩家是幂等的。
     */
    private static void awardIntBlockPos(MinecraftServer server) {
        AdvancementHolder holder = server.getAdvancements().get(InfsFarlands.id("int-block-pos"));
        if (holder == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.getAdvancements().award(holder, "impossible");
        }
    }

    public static void trimSectionLookup(int tickCount) {
        int beforeSize = SectionUtil.size();
        SectionUtil.trim(tickCount);
        int afterSize = SectionUtil.size();
        if (FarlandsConfig.logSectionLookupTrim) {
            InfsFarlands.LOGGER.info("Trimmed SectionUtil.lookup, before: {}, after: {}", beforeSize, afterSize);
        }
    }

    private static void trimAquiferLookup(int tickCount) {
        int beforeSize = AquiferUtil.size();
        AquiferUtil.trim(tickCount);
        int afterSize = AquiferUtil.size();
        if (FarlandsConfig.logAquiferLookupTrim) {
            InfsFarlands.LOGGER.info("Trimmed AquiferUtil.lookup, before: {}, after: {}", beforeSize, afterSize);
        }
    }

    /**
     * 该维度出现 vanilla 噪声系统时，给还在世上的玩家补发进度与经验。
     *
     * <p>
     * 不能在 VanillaNoiseSystem 构造时发：它跑在 createLevels 里，那时玩家还没进世界，
     * 专用服务器上第一个玩家可能在播种之后很久才登录。这里每 tick 检查，按完成前后各读一次
     * isDone 判定，只在完成的那一 tick 给一次经验。
     */
    private static void awardNewVanillaNoiseSystem(MinecraftServer server) {
        AdvancementHolder holder = server.getAdvancements().get(InfsFarlands.id("new-vanilla-noise-system"));
        if (holder == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (((LevelSystems) level).terrainSystem() instanceof VanillaNoiseSystem) {
                for (ServerPlayer player : level.players()) {
                    PlayerAdvancements advancements = player.getAdvancements();
                    AdvancementProgress progress = advancements.getOrStartProgress(holder);
                    boolean wasDone = progress.isDone();

                    advancements.award(holder, "impossible");
                    if (!wasDone && progress.isDone()) {
                        player.giveExperiencePoints(64);
                    }
                }
            }
        }
    }

    /**
     * skys-the-limit 的三项判定与经验：系统是 beta173、顶部渐消关闭、玩家 Y 达到阈值。
     *
     * <p>
     * 三项各是一条 criterion，在同一份进度里各自成一组，都须达成。坐标判定放在 beta173 这一支之内，
     * 到该 Y 与在该系统下因此是同一瞬间成立。
     *
     * <p>
     * 经验在完成的那一 tick 给一次。award 的返回值只说那一条 criterion 是不是新授予的，三条里任何
     * 一条都可能早于完成，所以按完成前后各读一次 isDone 判定，与 vanilla 授予 rewards 的判据同形。
     */
    private static void awardSkyLimit(MinecraftServer server) {
        AdvancementHolder holder = server.getAdvancements().get(InfsFarlands.id("skys-the-limit"));
        if (holder == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (((LevelSystems) level).terrainSystem() instanceof Beta173NoiseSystem beta173) {
                for (ServerPlayer player : level.players()) {
                    PlayerAdvancements advancements = player.getAdvancements();
                    AdvancementProgress progress = advancements.getOrStartProgress(holder);
                    boolean wasDone = progress.isDone();

                    advancements.award(holder, "beta173_system");
                    if (!beta173.topFadeEnabled()) {
                        advancements.award(holder, "top_fade_disabled");
                    }
                    if (player.getY() >= 2008131844) {
                        advancements.award(holder, "y_above_threshold");
                    }
                    if (!wasDone && progress.isDone()) {
                        player.giveExperiencePoints(171103);
                    }
                }
            }
        }
    }

    /**
     * 每 tick 末尾要跑一次的钩子，由兼容层在自己初始化时登记。核心不认识登记者，也不 import 它。
     *
     * <p>用途是那些「需要每 tick 推进一步、但又不属于本类职责」的兼容逻辑，例如 Chunky 预生成的驱动
     * 与完成判定。登记发生在模组初始化期，那时是单线程，此后只读。
     */
    private static final List<Consumer<MinecraftServer>> TICK_SINKS = new CopyOnWriteArrayList<>();

    /** 登记一个每 tick 末尾的回调。 */
    public static void addTickSink(Consumer<MinecraftServer> sink) {
        TICK_SINKS.add(sink);
    }

    /**
     * 只刷两张表：玩家位置快照与实体 section 窗口并集。
     *
     * <p>准入的两道门读的就是这两张：{@code isNearPlayer} 读快照，{@code Scheduler.sectionsFor} 的竖直面
     * 读窗口并集。抽出来的理由是提交点也要能只刷这一层：提交发生在阻塞取数之内，而票据的加减不该跟着
     * 提前到那一处。
     */
    public static void refreshGates(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        PlayerSnapshot.capture(players);
        EntitySectionWindow.update(players);
    }

    /**
     * 每 tick 的三步刷新：两张表加驱动面 ±8 的保加载票。
     *
     * <p>抽成方法是为了让真 tick 的末尾与阻塞等待期的泵共用同一份顺序。两处各写一份顺序，就会刷出与真
     * tick 不同源的状态，而两边同时被准入读。
     */
    public static void refreshTickState(MinecraftServer server) {
        refreshGates(server);
        NeighborhoodTickets.tick(server);
    }

    /** 服务端 tick 末尾统一入口。 */
    public static void atEnd(MinecraftServer server, int tickCount) {
        setNow(tickCount);
        awardNewVanillaNoiseSystem(server);
        awardSkyLimit(server);
        if (tickCount % INTERVAL == 0) {
            swapBlockLookup(server, tickCount);
            trimSectionLookup(tickCount);
            trimAquiferLookup(tickCount);
            // 就绪吞吐读数：与本周期同档，约 10 秒一行。只报读数，不报解读。
            StageMetrics.flush();
            // 视距内兜底重扫：把有活却不在候选表里的 chunk 补交一次，本拍末尾的 tick 统一派发。
            com.inf.farlands.terrain.pipeline.InViewSweep.run(server);
        }
        // 窗口差量与限量发包；返回值同时驱动 fsa 的窗口清理判定。
        boolean windowChanged = ChunkDataSender.tick(server);
        // 世界系统选择下发：玩家进服或换维度后补一次，客户端据此显示当前 level 用哪五族系统。
        SystemsSender.tick(server);
        // 玩家跨 chunk 移动时，光照队列按当前距离重排。地形侧的候选表在准入时按快照排序，不需要重排。
        if (ChunkDataSender.consumePlayersMoved()) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getChunkSource().getLightEngine() instanceof FarLandsLightEngine lightEngine) {
                    lightEngine.rebuildLightQueue();
                }
            }
        }
        // 玩家快照、窗口并集与驱动面票圈，同一份玩家列表、同一时点落地。阻塞等待期的泵也走这一条。
        refreshTickState(server);

        // fsa 序列化

        // 周期持久化：窗口内脏 section 写盘；偏移表与数据同节奏落盘，否则运行中磁盘偏移表
        // 陈旧，重进时 getSlot 错位丢 section。崩溃与强退兜底。
        if (tickCount % FarlandsConfig.fsaPersistInterval == 0) {
            SectionLifecycle.flushAllDirty(server);
            SectionIO.flushAllOffsetTables();
        }
        // 窗口变化：窗口之外的 section 按档处置，上限 CLEANUP_BUDGET/tick。
        // 未清完则下 tick 续跑：窗口静止时它不再变化，只按 windowChanged 触发会让积压永远留着。
        if (windowChanged || cleanupOpen) {
            cleanupOpen = SectionLifecycle.cleanup(server);
        }

        // 地形管线：调度器是地形侧唯一的每 tick 入口，准入、派发、重扫都在里面。
        Scheduler.tick();
        // 兼容层的每 tick 钩子，排在所有核心驱动之后：它只驱动本 port 之外的请求方，例如 Chunky 预生成。
        for (Consumer<MinecraftServer> sink : TICK_SINKS) {
            sink.accept(server);
        }
    }
}
