package com.inf.farlands.mixin.terrain.pipeline;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionLifecycle;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断：入场等待那一面的实体加载读数。
 *
 * <p>玩家入场要过 {@code ServerLevel.waitForEntities}，谓词是范围内每一格 areEntitiesLoaded 为真，
 * 而那个值只在该 chunk 收到一次 FullChunkStatus 通知、并完成一次实体加载之后才可能为真。本类在谓词
 * 求值之前把整面打一次，四栏一起给：偏移、票位、由票位换算的完整档、加载状态表的原始值。
 *
 * <p>加载状态只有三态：FRESH 表示从未请求，PENDING 表示请求已发、回包未落，LOADED 表示已落地。
 * 它与票位分开读，就能分开「没有请求」与「请求发了没落地」两件事。
 *
 * <p>只打三行，只在 logScheduler 打开时输出。定位完成后与同批探针一并删除，不混进正式改动。
 */
@Mixin(ServerLevel.class)
public class WaitForEntitiesMixin {

    /** 未加载明细一次最多列多少格。调用方半径 3 时上界 49，留一倍余量。 */
    @Unique
    private static final int farlands$LIST_LIMIT = 96;

    /** ServerLevel 的实体管理器字段。状态表挂在它上面，而它没有公开取数口。 */
    @Unique
    private static final Field farlands$F_ENTITY_MANAGER;

    /** 实体管理器的加载状态表。值类型是包私有枚举，所以按对象取、按名字打。 */
    @Unique
    private static final Field farlands$F_LOAD_STATUSES;

    static {
        try {
            farlands$F_ENTITY_MANAGER = ServerLevel.class.getDeclaredField("entityManager");
            farlands$F_ENTITY_MANAGER.setAccessible(true);
            farlands$F_LOAD_STATUSES = PersistentEntitySectionManager.class
                    .getDeclaredField("chunkLoadStatuses");
            farlands$F_LOAD_STATUSES.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: reflection on entity load status failed", e);
        }
    }

    /** 该 chunk 的加载状态名。表有默认值 FRESH，所以缺键不会返回 null。 */
    @Unique
    private static String farlands$loadStatus(ServerLevel level, long chunkKey) {
        try {
            Object manager = farlands$F_ENTITY_MANAGER.get(level);
            Object statuses = farlands$F_LOAD_STATUSES.get(manager);
            Object status = ((Long2ObjectMap<?>) statuses).get(chunkKey);
            return status == null ? "none" : status.toString();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: failed to read chunkLoadStatuses", e);
        }
    }

    @Inject(method = "waitForEntities(Lnet/minecraft/world/level/ChunkPos;I)V", at = @At("HEAD"))
    private void farlands$logEntityLoadFace(ChunkPos centerChunk, int radius, CallbackInfo ci) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        int total = 0;
        int loaded = 0;
        int noHolder = 0;
        int listed = 0;
        Map<String, Integer> histogram = new LinkedHashMap<>();
        StringBuilder notLoaded = new StringBuilder();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centerChunk.x() + dx, centerChunk.z() + dz);
                total++;
                String status = farlands$loadStatus(level, pos.pack());
                histogram.merge(status, 1, Integer::sum);
                if ("LOADED".equals(status)) {
                    loaded++;
                    continue;
                }
                ChunkHolder holder = SectionLifecycle.latestHolder(level, pos.x(), pos.z());
                if (holder == null) {
                    noHolder++;
                }
                if (listed < farlands$LIST_LIMIT) {
                    notLoaded.append('(').append(dx).append(',').append(dz);
                    if (holder == null) {
                        notLoaded.append(",noHolder");
                    } else {
                        int ticketLevel = holder.getTicketLevel();
                        notLoaded.append(",lvl=").append(ticketLevel)
                                .append(",st=").append(ChunkLevel.fullStatus(ticketLevel));
                    }
                    notLoaded.append(",ls=").append(status).append(')');
                    listed++;
                }
            }
        }
        InfsFarlands.LOGGER.info("FLWAIT center={},{} r={} total={} loaded={} noHolder={}",
                centerChunk.x(), centerChunk.z(), radius, total, loaded, noHolder);
        InfsFarlands.LOGGER.info("FLWAIT loadStatus {}", histogram);
        if (listed > 0) {
            InfsFarlands.LOGGER.info("FLWAIT notloaded listed={} {}", listed, notLoaded);
        }
    }
}
