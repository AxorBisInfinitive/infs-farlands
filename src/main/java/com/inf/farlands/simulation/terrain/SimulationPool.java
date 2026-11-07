package com.inf.farlands.simulation.terrain;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.serialize.SimulationIO;

/**
 * 模拟段的生产池，线程名 {@code farlands-sim}。
 *
 * <p>
 * 与 {@code farlands-gen} 分开，理由是本仓库已经踩过的那一次：编码池与生成池曾共用一个池，结果是 fill 被
 * 编码延迟。仿真生产的形状与编码相同，单段便宜、一次来几千个、与地形没有数据依赖；而地形任务是 fill 加
 * surface 加 carve，贵两个量级。固定线程池是 FIFO，没有优先级，混在一起会让 tick 内的地形推进整段推迟。
 *
 * <p>
 * 规模暂借用 {@link FarlandsConfig#genWorkerThreads}：仿真生产与地形生成同为纯算力，而当前的池规模配置只有
 * 它一条。两者将来要分开调时再加配置项。
 *
 * <p>
 * 在途计数在提交处加、在收尾回调里减，不在任务体末尾减。收尾是异步投回主线程的一笔，若在任务体里就减，
 * {@link #awaitIdle} 会在升档还没跑的时候放行。与装饰任务同一套规矩。
 *
 * <p>
 * 类里没有按世界的状态：计数不能清，旧世界可能有超时未收尾的任务仍在途，它们的出口会来递减这个计数，清零
 * 会被减成负数，下一个世界的关服等待就会误判成没有在途任务而直接放行。
 */
public final class SimulationPool {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, FarlandsConfig.genWorkerThreads), r -> {
                Thread t = new Thread(r, "farlands-sim");
                t.setDaemon(true);
                return t;
            });

    /** 已提交但收尾回调还没跑完的任务数。 */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private SimulationPool() {
    }

    /**
     * 提交一段的生产。返回假表示池已关闭，即停服中；调用方据此撤销它做的认领并留待下次。
     *
     * <p>
     * {@code onDoneMainThread} 在主线程执行，且先于在途计数递减。它负责条件判据与升档，抛异常也会保证计数
     * 递减。
     */
    public static boolean submitProduction(ServerLevel level, SimulationTask task, Runnable onDoneMainThread) {
        IN_FLIGHT.incrementAndGet();
        try {
            POOL.submit(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // 池任务走 submit，没人读那个 Future，任何 Throwable 都会被静默吞掉
                    InfsFarlands.LOGGER.error("farlands: simulation produce failed section={},{},{}",
                            task.key().x(), task.key().y(), task.key().z(), t);
                }
                SimulationIO.runOnMainThread(() -> {
                    try {
                        if (onDoneMainThread != null) {
                            onDoneMainThread.run();
                        }
                    } finally {
                        IN_FLIGHT.decrementAndGet();
                    }
                }, level);
            });
            return true;
        } catch (RejectedExecutionException e) {
            IN_FLIGHT.decrementAndGet();
            return false;
        }
    }

    public static boolean hasInflightWork() {
        return IN_FLIGHT.get() > 0;
    }

    public static int inFlight() {
        return IN_FLIGHT.get();
    }

    /**
     * 关服等待：等在途生产收敛，有界。超时由同步刷盘的调用方按各自的判据兜底。
     *
     * <p>
     * 循环里要抽主线程执行器：收尾回调投在主线程队列上，不抽它们，计数永远落不下来，这个等待只会等满超时。
     */
    public static void awaitIdle(MinecraftServer server, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            SimulationIO.drainMainThreadTasks(server);
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
}
