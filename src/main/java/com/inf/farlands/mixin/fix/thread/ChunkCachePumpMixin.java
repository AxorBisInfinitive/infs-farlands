package com.inf.farlands.mixin.fix.thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsTick;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.terrain.pipeline.Scheduler;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * chunk 执行器的等待期推进调度器。
 *
 * <p>阻塞取数的形状是 {@code ServerChunkCache.getChunk} 到 {@code mainThreadProcessor.managedBlock}，
 * 而那个循环体就是本类的注入点 {@code pollTask}。主线程停在这里时，{@code FarlandsTick.atEnd} 在
 * {@code tickServer} 的 RETURN 上跑不到，{@link EventLoopSchedulerPumpMixin} 又因为被阻塞的循环不是
 * 服务端自己的而被 {@code instanceof} 守卫挡掉，于是调度器一拍都不跑：等的那一格推进不了，取数永不返回。
 *
 * <p>{@code pollTask} 每轮都被调，所以这里推一轮，等待因此有进展。原版等待本身不动，本类只加一次推进。
 *
 * <p>三条约束与既有的泵同形。只在服务端线程上推；按墙钟节流到 50 毫秒，与调度器的预算窗口同档，
 * 否则等待循环会退化成零长度 park 加每秒上万次刷新快照与派发尝试；关服期不推。
 *
 * <p>第四条是本类独有的：泵自己会经准入的 READY 支调到 {@code Beardifier.forStructuresInChunk}，那条路
 * 经 {@code ServerChunkCache} 取 chunk，主线程上同样会阻塞。所以用一个线程局部闸把嵌套那一层降级成原版
 * 等待，不让它再进本方法。
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class ChunkCachePumpMixin {

    /**
     * 外层实例。目标类没有 level 字段，构造器只拿它拼线程名，所以服务端要从外层取。
     *
     * <p>这是 javac 生成的合成字段，名字随编译器而定；换编译器或换版本要重核。取不到时 Mixin 在应用期
     * 抛 {@code @Shadow field ... was not located}，而主配置 {@code required} 为真会让整个转换失败，
     * 属于响亮失败，不会静默不泵。
     */
    @Shadow
    @Final
    private ServerChunkCache this$0;

    /** 两次推进之间的最小间隔，单位毫秒。取一 tick 的量级，与既有的泵同值。 */
    @Unique
    private static final long farlands$PUMP_INTERVAL_MILLIS = 50L;

    /** 上一次推进的时刻。 */
    @Unique
    private static final AtomicLong farlands$lastPumpAt = new AtomicLong();

    /** 泵执行中标志：为真时嵌套进来的 {@code pollTask} 不再泵。 */
    @Unique
    private static final ThreadLocal<Boolean> farlands$IN_PUMP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 推进抛过异常没有，只记一次。 */
    @Unique
    private static final AtomicBoolean farlands$pumpFailed = new AtomicBoolean();

    @Inject(method = "pollTask", at = @At("HEAD"))
    private void farlands$pumpWhileWaiting(CallbackInfoReturnable<Boolean> cir) {
        if (farlands$IN_PUMP.get()) {
            return;
        }
        Level level = this.this$0.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
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
        farlands$IN_PUMP.set(Boolean.TRUE);
        Scheduler.beginPump();
        try {
            // 排在刷新与派发之前：提升会提交一次，提交自己会派发一轮；放在前面，本轮末尾的票传播仍赶得上。
            Scheduler.promotePendingWait();
            FarlandsTick.refreshTickState(server);
            Scheduler.tick();
        } catch (Throwable t) {
            if (farlands$pumpFailed.compareAndSet(false, true)) {
                InfsFarlands.LOGGER.error("farlands: chunk cache pump failed during a blocked wait", t);
            }
        } finally {
            Scheduler.endPump();
            farlands$IN_PUMP.remove();
        }
    }
}
