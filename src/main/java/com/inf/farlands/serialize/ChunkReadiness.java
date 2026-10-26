package com.inf.farlands.serialize;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import com.mojang.datafixers.util.Pair;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * FULL 的执行体与玩家写的段级门。
 *
 * <p>就绪的判据在调度器第七行：本类不判就绪，只在第七行成立时被调用。补 FULL 的判据是 FULL 那一格
 * future 的当前状态，不是「补过一次」：票级下降时 {@code failAndClearPendingFuturesBetween} 会把槽
 * 清空，之后 {@code scheduleChunkGenerationTask} 会建一个新的待完成 future，一次性令牌在那之后就会
 * 与读侧分叉。
 *
 * <p>玩家写的门是段级的：该段已到 LIGHTED 且不在读回。它不参与块级就绪，两件事不同面。
 */
public final class ChunkReadiness {

    /** 补 FULL 的入口，反射一次。与 GenerationChunkHolderMixin 里那份指向同一个私有方法。 */
    private static final Method M_COMPLETE_FUTURE;

    static {
        try {
            M_COMPLETE_FUTURE = GenerationChunkHolder.class.getDeclaredMethod("completeFuture",
                    ChunkStatus.class, ChunkAccess.class);
            M_COMPLETE_FUTURE.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("farlands: reflection on GenerationChunkHolder.completeFuture failed", e);
        }
    }

    /** 关服标志：置位后存盘门改看 isChunkBusy，让窗口永不建立时也写得下 chunk NBT。 */
    private static volatile boolean shuttingDown;

    private ChunkReadiness() {
    }

    /** 按位置取当前的 LevelChunk，未建壳返回 null。非阻塞，供写入门与预加载用。主线程调。 */
    public static LevelChunk chunkAt(ServerLevel level, ChunkPos pos) {
        return SectionLifecycle.latestChunk(level, pos.x(), pos.z());
    }

    /**
     * 该 chunk 的那个段是否可写：判据落在目标段自己身上。
     *
     * <p>两条。本段已到 LIGHTED，没到就说明它还在 fill、surface、carve、装饰或光照里，写下去会被覆盖
     * 或落进空段；本段此刻不在读回，读回会整体替换该段容器。
     *
     * <p>装饰不参与判定。装饰的段写取 {@code SectionSerializer.packLockFor} 的同一把锁，与玩家写并发时
     * 每格后写者胜，撞不坏容器；而认领是按 XZ 写域占一整块的，一次跨段会让视距内几乎每个 chunk 都成为
     * 装饰中心，认领若参与本判据，玩家要在整片视距内等它排完队。
     */
    private static boolean isWritableAt(LevelChunk chunk, int sectionY) {
        if (!SectionStage.isTerminal(SectionStage.getStage(chunk, sectionY))) {
            return false;
        }
        return !SectionIO.isReading(chunk, sectionY);
    }

    /** 该位置的那个段是否可写。没建壳即不可写。主线程调。 */
    public static boolean isReady(ServerLevel level, ChunkPos pos, int sectionY) {
        LevelChunk chunk = chunkAt(level, pos);
        return chunk != null && isWritableAt(chunk, sectionY);
    }

    /**
     * 补 FULL：只调反射。判据是 FULL 那一格 future 的当前状态，可重入。
     *
     * <p>
     * 三态。槽为 null 或 future 未完成才补；future 已完成一律跳过。已完成而失败的那一态必须跳过：
     * {@code completeFuture} 在它上面 {@code complete} 返回假、{@code isSuccess} 也为假，于是
     * {@code Thread.yield} 重试，永不返回。
     *
     * <p>
     * 先取 holder 再判：holder 暂时取不到时不写任何状态，该 chunk 这一代仍有下一次机会。
     */
    public static void completeFull(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkHolder holder = SectionLifecycle.latestHolder(level, chunk.getPos().x(), chunk.getPos().z());
        if (holder == null) {
            return;
        }
        CompletableFuture<ChunkResult<ChunkAccess>> future = farlands$fullFuture(holder);
        if (future != null && future.isDone()) {
            if (future.getNow(GenerationChunkHolder.UNLOADED_CHUNK).isSuccess()) {
                // 读数同步：这一代已经补过，账上的「补过」标记跟上。
                ChunkCounters.claimFull(chunk);
            }
            return;
        }
        try {
            M_COMPLETE_FUTURE.invoke(holder, ChunkStatus.FULL, chunk);
        } catch (InvocationTargetException e) {
            // 竞态只有一种形态：另一个完成者刚把它补成成功。复查状态，已落定就按成功返回。
            CompletableFuture<ChunkResult<ChunkAccess>> again = farlands$fullFuture(holder);
            if (again != null && again.isDone()) {
                return;
            }
            throw new RuntimeException("farlands: failed to complete FULL for " + chunk.getPos(), e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("farlands: failed to complete FULL for " + chunk.getPos(), e);
        }
        ChunkCounters.claimFull(chunk);
    }

    /** FULL 那一格的 future，槽为空返回 null。 */
    private static CompletableFuture<ChunkResult<ChunkAccess>> farlands$fullFuture(ChunkHolder holder) {
        for (Pair<ChunkStatus, CompletableFuture<ChunkResult<ChunkAccess>>> pair : holder.getAllFutures()) {
            if (pair.getFirst() == ChunkStatus.FULL) {
                return pair.getSecond();
            }
        }
        return null;
    }

    /** 关服入口置位，由 MinecraftServerMixin 在 stopServer 的 HEAD 调。 */
    public static void markShuttingDown() {
        shuttingDown = true;
    }

    public static boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * 停服时复位关服标志。
     *
     * <p>标志只置位不复位的话，同一个进程里再开一个世界时，存盘门的语义就错了。复位必须晚于
     * vanilla 的 saveAllChunks，所以调用点在 stopServer 的 RETURN。
     */
    public static void clearAll() {
        shuttingDown = false;
    }
}
