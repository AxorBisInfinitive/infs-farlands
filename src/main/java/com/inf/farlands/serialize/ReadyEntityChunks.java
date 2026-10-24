package com.inf.farlands.serialize;

import java.lang.reflect.Field;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/**
 * 入场等待之前，把已经备好数据的 chunk 的实体状态同步一次。
 *
 * <p>为什么要有这一步：入场等待只看 {@code areEntitiesLoaded}，而那个值只由实体侧收到一次
 * {@code FullChunkStatus} 通知、再完成一次读盘之后才为真。通知由 {@code ChunkHolder} 的确认 future
 * 发出，而 {@code ENTITY_TICKING} 那一档的确认要求该 chunk 的 5 乘 5 邻域都到 {@code FULL}。于是入场
 * 需要的 7 乘 7 实际要求 11 乘 11 的 {@code FULL}，而那个圈的中心由 {@code PlayerSpawnFinder.findSpawn}
 * 决定，在登录之前算不出来。等不到确认的格会一直停在默认的 {@code FRESH}，等待因此不解。
 *
 * <p>本类不做预测，也不铺面：它在等待发起的那一刻按真实中心逐格判四件事，判过就按 {@code FULL}
 * 一档把状态推一次。{@code FULL} 映射到 {@code TRACKED}，非 {@code HIDDEN} 即发起实体读盘，但不宣告
 * 实体刻，刻的时序仍留给原版后续的 promotion。
 *
 * <p>四道门缺一不可。身份那道必须排在数据那道之前：{@link SectionStage#hasBelowLighted} 对空档位表
 * 返回假，身份已注销的 chunk 会因此被误判成就绪。
 */
public final class ReadyEntityChunks {

    /** {@code ServerLevel.entityManager}，没有公开取数口，只能反射一次。 */
    private static final Field F_ENTITY_MANAGER;

    static {
        try {
            F_ENTITY_MANAGER = ServerLevel.class.getDeclaredField("entityManager");
            F_ENTITY_MANAGER.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: reflection on ServerLevel.entityManager failed", e);
        }
    }

    private ReadyEntityChunks() {
    }

    /**
     * 把中心与半径给定的那一圈同步一次。幂等：已是 {@code PENDING} 或 {@code LOADED} 的 chunk 不会重复
     * 请求读盘。
     */
    public static void sync(ServerLevel level, ChunkPos center, int radius) {
        int synced = 0;
        int noHolder = 0;
        int levelShort = 0;
        int dead = 0;
        int below = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(center.x() + dx, center.z() + dz);
                ChunkHolder holder = SectionLifecycle.latestHolder(level, pos.x(), pos.z());
                if (holder == null) {
                    noHolder++;
                    continue;
                }
                if (!ChunkLevel.fullStatus(holder.getTicketLevel()).isOrAfter(FullChunkStatus.FULL)) {
                    levelShort++;
                    continue;
                }
                LevelChunk chunk = SectionLifecycle.latestChunk(level, pos.x(), pos.z());
                if (chunk == null || !CounterIdentities.owned(chunk)) {
                    dead++;
                    continue;
                }
                if (SectionStage.hasBelowLighted(chunk)) {
                    below++;
                    continue;
                }
                push(level, pos);
                synced++;
            }
        }
        if (FarlandsConfig.logScheduler) {
            InfsFarlands.LOGGER.info(
                    "FLSYNC center={},{} r={} synced={} noHolder={} levelShort={} dead={} below={}",
                    center.x(), center.z(), radius, synced, noHolder, levelShort, dead, below);
        }
    }

    /** 把该格推到 FULL 一档。字段取不到时抛，不静默跳过。 */
    private static void push(ServerLevel level, ChunkPos pos) {
        try {
            Object manager = F_ENTITY_MANAGER.get(level);
            ((PersistentEntitySectionManager<?>) manager).updateChunkStatus(pos, FullChunkStatus.FULL);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: failed to sync entity chunk status", e);
        }
    }
}
