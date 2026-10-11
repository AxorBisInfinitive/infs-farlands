package com.inf.farlands.terrain.pipeline;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.util.network.ChunkDataSender;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 地形管线的对外门面：两张票、生成池、装饰提交、光照衔接、关服等待。
 *
 * <p>本类不再自己排队与扫描：候选、准入、认领与兜底重扫都在 {@link Scheduler}。这里保留类名与
 * 全部对外签名，是为了让命令层、兼容层、fsa 与网络层的调用点一行都不改，内部一律转发。
 *
 * <p>两张票型仍在这里注册，因为 {@code SpawnPreload}、{@code NeighborhoodTickets} 与
 * {@code BuiltInRegistriesMixin} 直接引用它们。
 */
public final class GenQueue {

    /**
     * fill 任务期间保持 chunk 加载的 ticket，半径 0，只保加载不卸载。
     *
     * <p>26.1.2 的 TicketType 是 record，没有 mod 侧静态工厂，类型须注册进
     * BuiltInRegistries.TICKET_TYPE，且必须在 freeze 之前。注册时机由 BuiltInRegistriesMixin
     * 在 bootStrap() 头部调 ensureTicketTypeRegistered() 把本类 clinit 提前，类初始化恰好一次。
     */
    public static final TicketType GEN_WORK_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            InfsFarlands.id("gen_work"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

    /** 结构依赖的 pin 票：给中心引用相要读的加减 8 起点表那些邻居铺的加载票。 */
    public static final TicketType STRUCTURE_PIN_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            InfsFarlands.id("structure_pin"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

    /** 触发本类 clinit，即上面两张票型的注册。仅供 BuiltInRegistriesMixin 在冻结前调用。 */
    public static TicketType ensureTicketTypeRegistered() {
        return GEN_WORK_TICKET;
    }

    /** 作业池。规模在配置层已解析，恒大于等于 1。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(FarlandsConfig.genWorkerThreads, r -> {
        Thread t = new Thread(r, "farlands-gen");
        t.setDaemon(true);
        return t;
    });

    /** 装饰任务在途计数，关服等待用。加在提交处，减在装饰的收尾里。 */
    private static final AtomicInteger DECORATION_IN_FLIGHT = new AtomicInteger();

    private GenQueue() {
    }

    // ---- 票 ----

    /** 铺一张结构依赖票。主线程调用。 */
    public static void pinStructureDependency(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addTicketWithRadius(STRUCTURE_PIN_TICKET, pos, 0);
    }

    /** 撤一张结构依赖票。调用点里有卸载线程，所以自己 marshal 回主线程。半径必须与铺下去的一致。 */
    public static void unpinStructureDependency(ServerLevel level, ChunkPos pos) {
        SectionIO.runOnMainThread(
                () -> level.getChunkSource().removeTicketWithRadius(STRUCTURE_PIN_TICKET, pos, 0), level);
    }

    // ---- 池 ----

    /** 把一个生成作业交给池。主线程调用。 */
    static void submitGen(GenTask task) {
        try {
            POOL.submit(task::execute);
        } catch (RejectedExecutionException e) {
            // 池已关闭，即停服：走一次收尾把认领与票还回去，数据由重进重生成兜底
            Scheduler.finishJob(task, true);
        }
    }

    /** 把装饰任务提交到池。返回假表示池已关，调用方撤认领、留表稍后再试。 */
    public static boolean submitDecoration(Runnable job) {
        DECORATION_IN_FLIGHT.incrementAndGet();
        try {
            POOL.submit(job);
            return true;
        } catch (RejectedExecutionException e) {
            DECORATION_IN_FLIGHT.decrementAndGet();
            return false;
        }
    }

    /** 装饰收尾完成时减计数。由装饰的收尾调用，主线程。 */
    public static void finishDecoration() {
        DECORATION_IN_FLIGHT.decrementAndGet();
    }

    /** 装饰在途计数，供关服等待判据。 */
    static int decorationInFlight() {
        return DECORATION_IN_FLIGHT.get();
    }

    // ---- 候选入口，全部转发调度器 ----

    /** 主线程上的单 section 请求。 */
    public static void enqueue(LevelChunk chunk, int sectionY) {
        Scheduler.enqueue(chunk, sectionY, Scheduler.Origin.SECTION);
    }

    /** chunk 在主线程短路完成时按 XZ 触发。 */
    public static void enqueueChunk(LevelChunk chunk) {
        Scheduler.enqueueChunk(chunk, Scheduler.Origin.CHUNK);
    }

    /** 预加载：指定段带入队，绕过 tracking view 过滤。 */
    public static void preload(LevelChunk chunk, int minSy, int maxSy) {
        Scheduler.preload(chunk, minSy, maxSy, Scheduler.Origin.PRELOAD);
    }

    /** 该 chunk 是否在任一玩家的驱动面内。 */
    public static boolean isNearPlayer(LevelChunk chunk) {
        return Scheduler.isNearPlayer(chunk);
    }

    /** 每 tick 唤醒：事件 drain、候选派发、兜底重扫。 */
    public static void tick(MinecraftServer server) {
        Scheduler.tick(server, com.inf.farlands.FarlandsTick.getNow());
    }

    /** 玩家位置变化时重排候选。 */
    public static void rebuildQueue() {
        Scheduler.rebuild();
    }

    // ---- 在途判据 ----

    public static boolean isGeneratingOrLighting(LevelChunk chunk) {
        return Scheduler.isGeneratingOrLighting(chunk);
    }

    public static boolean isChunkBusy(LevelChunk chunk) {
        return Scheduler.isChunkBusy(chunk);
    }

    public static boolean isBiomeFilling(LevelChunk chunk) {
        return Scheduler.isBiomeFilling(chunk);
    }

    public static boolean isLightInFlight(LevelChunk chunk) {
        return Scheduler.isLightInFlight(chunk);
    }

    public static void beginBiomeFill(LevelChunk chunk) {
        Scheduler.beginBiomeFill(chunk);
    }

    public static void endBiomeFill(LevelChunk chunk) {
        Scheduler.endBiomeFill(chunk);
    }

    // ---- 光照衔接 ----

    /** 报告某 section 已生成，触发光照。 */
    public static void notifyGenerated(LevelChunk chunk) {
        Scheduler.notifyGenerated(chunk);
    }

    /**
     * 一次 fillFrom 与 lightChunk，完成回调里升段、释放、再检查。
     *
     * <p>升段与广播都在主线程那一笔里做：光照覆盖全 chunk，回调线程通常是光照池，而升档要更新记账、
     * 广播要发包、标记要补发，三件都要求主线程。
     */
    static void triggerLight(LevelChunk chunk) {
        ServerLevel serverLevel = (ServerLevel) chunk.getLevel();
        if (serverLevel.getChunkSource().getLightEngine() instanceof FarLandsLightEngine lightEngine) {
            chunk.initializeLightSources();
            lightEngine.onChunkSkySourcesReady(chunk.getPos());
            long lightStart = System.nanoTime();
            lightEngine.lightChunk(chunk, false).whenComplete((c, t) -> {
                StageMetrics.stageWork(StageMetrics.STAGE_LIGHTED, System.nanoTime() - lightStart);
                if (t == null) {
                    SectionStage.promoteAllGenToLighted(chunk);
                    SectionIO.runOnMainThread(() -> {
                        ChunkDataSender.broadcastChunkLight(serverLevel, chunk);
                        ChunkDataSender.markChunkChanged(chunk);
                        ChunkReadiness.drive();
                    }, serverLevel);
                } else {
                    InfsFarlands.LOGGER.error("farlands: light failed chunk={}", chunk.getPos(), t);
                }
                Scheduler.lightFinished(chunk);
            });
        } else {
            // 防御：非本 port 光引擎，释放标志防卡死
            Scheduler.lightFinished(chunk);
        }
    }

    // ---- 生命周期 ----

    /** 全局是否仍有生成、光照或装饰在途。 */
    public static boolean hasInflightWork() {
        return Scheduler.hasInflightWork();
    }

    /**
     * 关服等待：等候选队列空且认领表空，有界。每轮唤醒消费并抽一次主线程执行器，因为群系收尾与读回
     * 完成都投回主线程跑，不抽它们那些在途标志永远落不下来。
     */
    public static void awaitIdle(MinecraftServer server, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Scheduler.tick(server, com.inf.farlands.FarlandsTick.getNow());
            SectionIO.drainMainThreadTasks(server);
            if (!hasInflightWork()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 停服时清空候选、事件与所有权。 */
    public static void clearWorldState() {
        Scheduler.clearWorldState();
    }
}
