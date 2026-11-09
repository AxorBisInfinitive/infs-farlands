package com.inf.farlands.client.simulation;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.model.SimulationConfig;

/**
 * 模拟段的烘焙池，线程名 {@code farlands-mesh}。
 *
 * <p>
 * 只跑纯计算：{@link SimulationMesher#build} 读快照、产出顶点缓冲，不碰显存、不碰段表、不碰客户端单例。显存上传
 * 留在渲染线程，因为 GL 入口逐条断言渲染线程。
 *
 * <p>
 * 完成结果进一条并发队列，由渲染线程每 tick 取走。不用主线程执行器，是因为取走这一步本来就在 tick 里，多一层调度
 * 只会把「任务完成」与「结果可见」拆成两个时点。
 *
 * <p>
 * 在途计数在提交处加、在主线程取走结果时减。上限存在的理由不是省内存，而是防积压：一段的内容在途期间可能又变了，
 * 排太多只会烘出过期的结果。
 */
final class SimulationMeshPool {

    /** 一次完成：快照与它的烘制结果。结果本身为 null 表示烘制抛了异常，三层都没有。 */
    record Completed(BakeSnapshot snapshot, SimulationMesher.Build build) {
    }

    private static final int THREADS = Math.max(1, SimulationConfig.meshWorkerThreads);

    /**
     * 在途上限。一 tick 只取走一次完成结果，所以它必须容得下「一 tick 内所有工作线程能烘完的量」，否则吞吐会被
     * 取走频率卡住。每条线程按单段 2.7 毫秒、50 毫秒一 tick 算约 18 段，取 24 留余量。
     */
    private static final int MAX_IN_FLIGHT = Math.max(64, THREADS * 24);

    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, runnable -> {
        Thread thread = new Thread(runnable, "farlands-mesh");
        thread.setDaemon(true);
        return thread;
    });

    private static final Queue<Completed> COMPLETED = new ConcurrentLinkedQueue<>();

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private SimulationMeshPool() {
    }

    static int threads() {
        return THREADS;
    }

    /** 还能提交几段。 */
    static int freeSlots() {
        return MAX_IN_FLIGHT - IN_FLIGHT.get();
    }

    /**
     * 提交一段的烘焙。任务体吞掉一切可抛物并照常投回结果，调用方因此只需处理「有网格」与「没有网格」两态。
     */
    static void submit(BakeSnapshot snapshot) {
        IN_FLIGHT.incrementAndGet();
        try {
            POOL.execute(() -> {
                SimulationMesher.Build build = null;
                try {
                    build = SimulationMesher.build(snapshot);
                } catch (Throwable t) {
                    InfsFarlands.LOGGER.error("farlands: simulation mesh failed section={},{},{}",
                            snapshot.key().x(), snapshot.key().y(), snapshot.key().z(), t);
                }
                COMPLETED.add(new Completed(snapshot, build));
            });
        } catch (RejectedExecutionException e) {
            IN_FLIGHT.decrementAndGet();
        }
    }

    /** 取走一条完成结果，没有则 null。取走即释放一个在途名额。 */
    static Completed take() {
        Completed done = COMPLETED.poll();
        if (done != null) {
            IN_FLIGHT.decrementAndGet();
        }
        return done;
    }

    /** 丢弃全部未取走的结果，换关卡时用。顶点缓冲是直接内存，逐条释放。 */
    static void discardCompleted() {
        Completed done;
        while ((done = take()) != null) {
            SimulationMesher.release(done.build());
        }
    }
}
