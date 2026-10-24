package com.inf.farlands.mixin.fix.thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.terrain.pipeline.Scheduler;

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
 * <p>只推 {@code Scheduler.tick()} 一段，不是整个 tick：发包、窗口并集、玩家快照与周期持久化仍只在真 tick
 * 里跑。异常一律吞掉并只记一次，否则它会把原版的任务等待循环打断。
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

    @Redirect(method = "managedBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;waitForTasks()V"))
    private void farlands$pumpSchedulerWhileWaiting(BlockableEventLoop<?> self) {
        this.farlands$pumpScheduler();
        this.waitForTasks();
    }

    @Unique
    private void farlands$pumpScheduler() {
        if (!((Object) this instanceof MinecraftServer server)) {
            return;
        }
        if (Thread.currentThread() != server.getRunningThread()) {
            return;
        }
        if (ChunkReadiness.isShuttingDown()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = farlands$lastPumpAt.get();
        if (now - last < farlands$PUMP_INTERVAL_MILLIS) {
            return;
        }
        if (!farlands$lastPumpAt.compareAndSet(last, now)) {
            return;
        }
        try {
            Scheduler.tick();
        } catch (Throwable t) {
            if (farlands$pumpFailed.compareAndSet(false, true)) {
                InfsFarlands.LOGGER.error("farlands: scheduler pump failed during a blocked wait", t);
            }
        }
    }
}
