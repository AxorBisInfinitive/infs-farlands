package com.inf.farlands.serialize;

import java.lang.reflect.Method;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * FULL 的执行体与玩家写的段级门。
 *
 * <p>就绪的判据已收进调度器的第七行：本类不再判就绪，也不再观察登记表，只在第七行成立时被调用。
 * 单次完成以 {@link ChunkCounters#claimFull} 为令牌：{@code completeFuture} 对已完成的 future 再调会抛，
 * 那次领取把「补过」记在记账实例上，卸载随实例消亡。
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
        if (SectionStage.getStage(chunk, sectionY) < SectionStage.LIGHTED) {
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
     * 补 FULL：只调反射。判据在调度器的第七行，令牌在记账实例上。
     *
     * <p>取不到 holder 说明该 chunk 已不在可见表里，放弃本次；令牌已经领走，重载后是新的记账实例，
     * 令牌随之复位。
     */
    public static void completeFull(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!ChunkCounters.claimFull(chunk)) {
            return;
        }
        ChunkHolder holder = SectionLifecycle.latestHolder(level, chunk.getPos().x(), chunk.getPos().z());
        if (holder == null) {
            return;
        }
        try {
            M_COMPLETE_FUTURE.invoke(holder, ChunkStatus.FULL, chunk);
        } catch (Exception e) {
            throw new RuntimeException("farlands: failed to complete FULL for " + chunk.getPos(), e);
        }
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
