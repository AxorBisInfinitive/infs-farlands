package com.inf.farlands.terrain.pipeline;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 出生区预加载的 chunk 登记，供入场时释放预加载票。
 *
 * <p>票在 prepareLevels 里加上，必须留到该维度的第一个玩家入场：提前移除会让这些 chunk 在玩家到达前
 * 被卸载，再重载就是读回与写盘的竞态。按维度分桶是因为移除发生在入场玩家的维度上，若第一个进服的玩家
 * 不在出生维度，按单一集合清就会把票留在错误的维度，那些 chunk 永不卸载。
 */
public final class SpawnPreload {

    private static final Map<ResourceKey<Level>, Set<Long>> CHUNKS = new ConcurrentHashMap<>();

    /**
     * 世界创建期的驱动面：`setInitialSpawn` 的阻塞取数发生在玩家进名单之前，面上必须有东西可查，
     * 否则准入的面判据恒假。记下它的面与票，首个玩家入场时一并撤。
     */
    private record Creation(Scheduler.Face face, ChunkPos center, int radius) {
    }

    private static final Map<ResourceKey<Level>, Creation> CREATIONS = new ConcurrentHashMap<>();

    private SpawnPreload() {
    }

    /** 加票后登记。 */
    public static void register(ResourceKey<Level> dimension, ChunkPos pos) {
        CHUNKS.computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet()).add(pos.pack());
    }

    /** 登记创建期的面与那张大半径票。 */
    public static void registerCreation(ResourceKey<Level> dimension, Scheduler.Face face, ChunkPos center,
            int radius) {
        CREATIONS.put(dimension, new Creation(face, center, radius));
    }

    /** 停服时清空登记。票随进程结束即散，这里清的是内存里的条目。 */
    public static void clearAll() {
        CHUNKS.clear();
        CREATIONS.clear();
    }

    /** 该维度的第一个玩家入场时移除该维度的预加载票与创建期面，幂等。主线程。 */
    public static void release(ServerLevel level) {
        Set<Long> keys = CHUNKS.remove(level.dimension());
        if (keys != null) {
            for (long key : keys) {
                level.getChunkSource().removeTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, ChunkPos.unpack(key),
                        0);
            }
        }
        Creation creation = CREATIONS.remove(level.dimension());
        if (creation != null) {
            level.getChunkSource().removeTicketWithRadius(GenQueue.STRUCTURE_PIN_TICKET, creation.center(),
                    creation.radius());
            Scheduler.unregister(creation.face());
        }
    }
}
