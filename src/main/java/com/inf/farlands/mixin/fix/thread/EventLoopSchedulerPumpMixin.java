package com.inf.farlands.mixin.fix.thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.FarlandsTick;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.terrain.pipeline.SchedulerMetrics;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.PlayerSnapshot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 主线程在等任务时继续推进调度器。
 *
 * <p>阻塞取数的形状是：主线程进 {@code managedBlock} 的循环，抽到一个任务就执行它，队列空就
 * {@code waitForTasks} 让出。问题出在执行的那个任务本身又会阻塞：池线程要一个 chunk，投回的
 * {@code AsyncSupply} 在主线程上跑，进来以后同样落进 {@code getChunk} 的 {@code managedBlock}。而那个
 * chunk 要到 FULL 只能由调度器的第七行补，调度器却只在 tick 里跑；主线程此刻在 tick 之外的等待里，
 * tick 不再推进。于是池线程等主线程，主线程等 tick。
 *
 * <p>本类在每次让出之前把调度器推一轮，让 FULL 能在等待期间补上，等待因此有进展。
 *
 * <p>三条约束。只在服务端线程上推，{@code BlockableEventLoop} 同时被客户端、音效执行器与 chunk 主线程
 * 执行器继承，不加这条会把调度器泵进所有等待口。按墙钟节流，间隔与一 tick 同档，使
 * {@code maxGenTasksPerTick} 保持「每档一次」的语义：卡住之后等待循环会退化成零长度 park，不节流就是每秒
 * 上万次派发。关服期不推，那时池已开始关闭，派发没有意义。
 *
 * <p>推的是「每 tick 的三步状态刷新加 {@code Scheduler.tick()}」，不是整个 tick：发包、窗口差量、周期持久化
 * 与清理仍只在真 tick 里跑。
 *
 * <p>那三步刷新必须进来。准入的两道门读的就是它们：{@code isNearPlayer} 读玩家快照，{@code sectionsFor}
 * 的竖直面读窗口并集，而两者只在 {@code FarlandsTick.atEnd} 里刷新。atEnd 挂在 tickServer 的 RETURN，
 * 阻塞取数却在 RETURN 之前：玩家刚换维度时该维度既没有快照条目也没有窗口条目，准入因此永远放行不了那一次
 * 取数，等待再也解不开。泵是这一段唯一的钟，刷新必须与它同处。
 *
 * <p>异常一律吞掉并只记一次，否则它会把原版的任务等待循环打断。
 */
@Mixin(BlockableEventLoop.class)
public abstract class EventLoopSchedulerPumpMixin {

    @Shadow
    protected abstract void waitForTasks();

    /** 两次推进之间的最小间隔，单位毫秒。取一 tick 的量级。 */
    @Unique
    private static final long farlands$PUMP_INTERVAL_MILLIS = 50L;

    /** 上一次推进的时刻。 */
    @Unique
    private static final AtomicLong farlands$lastPumpAt = new AtomicLong();

    /** 推进抛过异常没有，只记一次。 */
    @Unique
    private static final AtomicBoolean farlands$pumpFailed = new AtomicBoolean();

    /** 心跳的最小间隔，单位毫秒。 */
    @Unique
    private static final long farlands$HEARTBEAT_MILLIS = 1000L;

    /** 上一次心跳的时刻。 */
    @Unique
    private static final AtomicLong farlands$lastBeatAt = new AtomicLong();

    /** 越过全部守卫、真的执行了的推进次数。 */
    @Unique
    private static final AtomicLong farlands$PUMPS = new AtomicLong();

    /** 四个早退分支各自的次数。与上一栏并排，就能分出「没被调」与「被闸挡住」。 */
    @Unique
    private static final AtomicLong farlands$SKIP_INSTANCE = new AtomicLong();

    @Unique
    private static final AtomicLong farlands$SKIP_THREAD = new AtomicLong();

    @Unique
    private static final AtomicLong farlands$SKIP_SHUTDOWN = new AtomicLong();

    @Unique
    private static final AtomicLong farlands$SKIP_THROTTLE = new AtomicLong();

    @Redirect(method = "managedBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;waitForTasks()V"))
    private void farlands$pumpSchedulerWhileWaiting(BlockableEventLoop<?> self) {
        this.farlands$pumpScheduler();
        this.waitForTasks();
    }

    @Unique
    private void farlands$pumpScheduler() {
        if (!((Object) this instanceof MinecraftServer server)) {
            farlands$SKIP_INSTANCE.incrementAndGet();
            return;
        }
        if (Thread.currentThread() != server.getRunningThread()) {
            farlands$SKIP_THREAD.incrementAndGet();
            return;
        }
        if (ChunkReadiness.isShuttingDown()) {
            farlands$SKIP_SHUTDOWN.incrementAndGet();
            return;
        }
        long now = System.currentTimeMillis();
        long last = farlands$lastPumpAt.get();
        if (now - last < farlands$PUMP_INTERVAL_MILLIS) {
            farlands$SKIP_THROTTLE.incrementAndGet();
            return;
        }
        if (!farlands$lastPumpAt.compareAndSet(last, now)) {
            farlands$SKIP_THROTTLE.incrementAndGet();
            return;
        }
        farlands$PUMPS.incrementAndGet();
        try {
            SchedulerMetrics.pumped(FarlandsTick.refreshTickState(server));
            Scheduler.tick("pump");
        } catch (Throwable t) {
            if (farlands$pumpFailed.compareAndSet(false, true)) {
                InfsFarlands.LOGGER.error("farlands: scheduler pump failed during a blocked wait", t);
            }
        }
        farlands$beat();
    }

    /**
     * 心跳：阻塞期每秒一行，报这次推进看到了什么。
     *
     * <p>这一行必须从泵内部打，不能借 {@code FLSCHED}：那一行挂在 200 tick 块里，而 200 tick 块挂在
     * {@code tickServer} 的 RETURN，正是冻结时跑不到的那一侧。泵的证据要由冻结期仍然执行的路径给出。
     *
     * <p>四栏合看：{@code pumps} 与四个 {@code skip} 分出「没被调」与「被闸挡住」；{@code players} 与
     * {@code dims} 报这次刷新看到谁；{@code bands} 是窗口表的实际内容；{@code subs} 与 {@code exits}
     * 的差值说明冻结期还有没有人往候选表里提交。
     *
     * <p>域锁那一族不在这里，在 {@code FLWAIT}。这一行只在被阻塞的循环是服务端自己的时候才打，而换维度
     * 之后那次阻塞取数落在 {@code ServerChunkCache$MainThreadExecutor} 上，本类的
     * {@code instanceof MinecraftServer} 守卫会把那一整段挡掉，实测 {@code skip(inst)} 一路涨而
     * {@code pumps} 停住。
     */
    @Unique
    private void farlands$beat() {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = farlands$lastBeatAt.get();
        if (now - last < farlands$HEARTBEAT_MILLIS || !farlands$lastBeatAt.compareAndSet(last, now)) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLPUMP pumps={} skip(inst/thread/shut/throttle)={}/{}/{}/{} players={} dims={} bands={} "
                        + "subs={} exits={} cands={}",
                farlands$PUMPS.get(), farlands$SKIP_INSTANCE.get(), farlands$SKIP_THREAD.get(),
                farlands$SKIP_SHUTDOWN.get(), farlands$SKIP_THROTTLE.get(),
                PlayerSnapshot.entries().size(), PlayerSnapshot.dimensions(),
                EntitySectionWindow.snapshot(), Scheduler.submittedTotal(), Scheduler.exitedTotal(),
                Scheduler.candidateCount());
    }
}
