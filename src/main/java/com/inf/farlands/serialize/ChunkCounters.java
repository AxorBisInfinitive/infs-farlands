package com.inf.farlands.serialize;

import java.util.Map;

import com.inf.farlands.util.map.Long2ObjectStripedMap;
import com.inf.farlands.util.window.WindowedChunk;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 每 chunk 的派生记账，供调度器的依赖判据 O(1) 取值。本节只记账，不改任何判据。
 *
 * <p>三项与两个一次性标志：
 * {@code pendingSurface}，已计入的段里档位低于 SURFACE 的个数，归零表示该 chunk 可以进 CARVERS；
 * {@code pendingDecorated}，档位低于 DECORATED 的个数，归零表示可以进 LIGHTED；
 * {@code terrainCursor}，已计入的段里档位低于 TERRAIN 的最高段号，空集取 {@link #NO_CURSOR}，
 * SURFACE(S) 的判据是 S 大于本值，即该段之上的已计入段都不欠地形。
 *
 * <p>两个标志：{@code windowReadDone} 是「窗口面读过一次盘」，{@code fullDone} 是「FULL 已补」。
 * 后者是第七行的单次完成令牌：{@code completeFuture} 对已完成的 future 再调会抛。
 *
 * <p>游标按 chunk 而不是按列：档位是按段的，一个段覆盖它 16 乘 16 的全部列，所以「S 之上的段
 * 欠不欠地形」与列无关。
 *
 * <p>「已计入」是决定性的一点。段可能先物化后升档，也可能先有档位后有条目，后者出现在非噪声
 * 生成器的维度里：fill 会早退，段没被建出来而档位已经推进。两个方向都要算一次且只算一次，所以
 * 每 chunk 另记一个已计入集合；缺了它，前一种情形会被重复计入，后一种会把计数减成负数。
 *
 * <p>键是身份编号，不是坐标。坐标共键会让三类写者混进同一格：单机下客户端与服务端两个同坐标的
 * chunk、同坐标的前一代与后一代，以及后一代构造时继承前一代的账。身份只发给服务端的 LevelChunk。
 *
 * <p>线程：段状态写点来自 genPool、backgroundExecutor 与主线程三处，所以增减与查询都在实例上同步。
 *
 * <p>锁序：调用方必须先放开段状态表与段容器的锁再调本类。
 */
public final class ChunkCounters {

    /** 空游标。段号可以取到 int 全域，所以不能用 0 表示空。 */
    public static final int NO_CURSOR = Integer.MIN_VALUE;

    private static final Long2ObjectStripedMap<ChunkCounters> COUNTERS = new Long2ObjectStripedMap<>(1 << 12);

    private final IntOpenHashSet accounted = new IntOpenHashSet();
    private int pendingSurface;
    private int pendingDecorated;
    private int terrainCursor = NO_CURSOR;

    /** 窗口内段读过一次盘即为真，一次性。 */
    private boolean windowReadDone;

    /** FULL 已补过，一次性。令牌：completeFuture 对已完成的 future 再调会抛。 */
    private boolean fullDone;

    private ChunkCounters() {
    }

    /** 记账快照，供自检比对。 */
    public record Snapshot(int pendingSurface, int pendingDecorated, int terrainCursor) {
    }

    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof CounterIdentity identity ? identity.counterId() : 0L;
    }

    /** 活着的身份，死的返回 0，写入整体空转。 */
    private static long liveIdentityOf(LevelChunk chunk) {
        long identity = identityOf(chunk);
        return CounterIdentities.isLive(identity) ? identity : 0L;
    }

    /**
     * 取或建该身份的记账。建完再判一次活：判活与建表之间可能刚好被注销，那张刚建的空表要立刻
     * 收回，否则它会成为没人再清的残留。
     */
    private static ChunkCounters ofLive(long identity) {
        ChunkCounters counters = COUNTERS.computeIfAbsent(identity, k -> new ChunkCounters());
        if (!CounterIdentities.isLive(identity)) {
            COUNTERS.remove(identity);
            return null;
        }
        return counters;
    }

    // ---- 四个接线点 ----

    /** 段被物化时计入。已计入则空转，所以重复物化与读回重放都安全；身份已死也空转。 */
    public static void onMaterialize(LevelChunk chunk, int sectionY) {
        // 诊断：物化按 chunk 计数，与同一格的移除数并排，看「移除后重建」这条环。
        com.inf.farlands.debug.CarveLoopProbe.materialize(chunk, sectionY);
        long identity = liveIdentityOf(chunk);
        if (identity != 0L) {
            ChunkCounters counters = ofLive(identity);
            if (counters != null) {
                counters.materialize(chunk, sectionY);
            }
        }
    }

    /** 档位变化时记账：先补记该段，再按跨越的档位减计数。 */
    public static void onStageChange(LevelChunk chunk, int sectionY, int oldStage, int newStage) {
        long identity = liveIdentityOf(chunk);
        if (identity != 0L) {
            ChunkCounters counters = ofLive(identity);
            if (counters != null) {
                counters.stageChange(chunk, sectionY, oldStage, newStage);
            }
        }
    }

    /** 段被移除时反向记账。 */
    public static void onRemove(LevelChunk chunk, int sectionY, int stage) {
        long identity = liveIdentityOf(chunk);
        if (identity != 0L) {
            ChunkCounters counters = ofLive(identity);
            if (counters != null) {
                counters.remove(chunk, sectionY, stage);
            }
        }
    }

    /**
     * 构造末尾的统一扫：段容器里的每一段各记一次。
     *
     * <p>判据取容器的键集，不取构造器那个数组，因为窗口默认段在容器里而不在数组里。已计入的段由
     * 集合去重，所以与之后的物化点不会重复计。ProtoChunk 与客户端 chunk 的身份是 0，直接返回。
     */
    public static void sweep(LevelChunk chunk) {
        long identity = liveIdentityOf(chunk);
        if (identity == 0L) {
            return;
        }
        ChunkCounters counters = ofLive(identity);
        if (counters == null) {
            return;
        }
        for (Integer sectionY : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            if (sectionY != null) {
                counters.materialize(chunk, sectionY);
            }
        }
    }

    // ---- 生命周期 ----

    /** chunk 卸载后清掉它自己那一格。按身份清，清不到同坐标的后一代。 */
    public static void clear(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity != 0L) {
            COUNTERS.remove(identity);
        }
    }

    /** 停服时清空。 */
    public static void clearAll() {
        COUNTERS.clear();
    }

    /** 读该 chunk 的记账快照；身份为 0 或从未记账过则全零。 */
    public static Snapshot snapshot(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return new Snapshot(0, 0, NO_CURSOR);
        }
        ChunkCounters c = COUNTERS.get(identity);
        if (c == null) {
            return new Snapshot(0, 0, NO_CURSOR);
        }
        synchronized (c) {
            return new Snapshot(c.pendingSurface, c.pendingDecorated, c.terrainCursor);
        }
    }

    /** 置窗口读回闩。窗口内段读过一次盘即为真，一次性；卸载随实例消亡。 */
    public static void markWindowReadDone(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return;
        }
        ChunkCounters c = COUNTERS.get(identity);
        if (c != null) {
            synchronized (c) {
                c.windowReadDone = true;
            }
        }
    }

    /** 窗口读回闩是否已置。身份为 0 或从未记账过返回假。 */
    public static boolean isWindowReadDone(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return false;
        }
        ChunkCounters c = COUNTERS.get(identity);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            return c.windowReadDone;
        }
    }

    /** FULL 补发令牌是否已被领走。只读，供诊断行用。 */
    public static boolean isFullDone(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return false;
        }
        ChunkCounters c = COUNTERS.get(identity);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            return c.fullDone;
        }
    }

    /** FULL 补发令牌：取到才算本次补。身份为 0 返回假。 */
    public static boolean claimFull(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return false;
        }
        ChunkCounters c = COUNTERS.get(identity);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            if (c.fullDone) {
                return false;
            }
            c.fullDone = true;
            return true;
        }
    }

    // ---- 实例实现，全部在实例上同步 ----

    private synchronized void materialize(LevelChunk chunk, int sectionY) {
        if (!this.accounted.add(sectionY)) {
            return;
        }
        int stage = SectionStage.getStage(chunk, sectionY);
        if (stage < SectionStage.SURFACE) {
            this.pendingSurface++;
        }
        if (stage < SectionStage.DECORATED) {
            this.pendingDecorated++;
        }
        if (stage < SectionStage.TERRAIN && sectionY > this.terrainCursor) {
            this.terrainCursor = sectionY;
        }
    }

    private synchronized void stageChange(LevelChunk chunk, int sectionY, int oldStage, int newStage) {
        if (this.accounted.add(sectionY)) {
            // 先有档位后有条目：按旧档位把这一段补记进来
            if (oldStage < SectionStage.SURFACE) {
                this.pendingSurface++;
            }
            if (oldStage < SectionStage.DECORATED) {
                this.pendingDecorated++;
            }
            if (oldStage < SectionStage.TERRAIN && sectionY > this.terrainCursor) {
                this.terrainCursor = sectionY;
            }
        }
        if (oldStage < SectionStage.SURFACE && newStage >= SectionStage.SURFACE) {
            this.pendingSurface--;
        }
        if (oldStage < SectionStage.DECORATED && newStage >= SectionStage.DECORATED) {
            this.pendingDecorated--;
        }
        if (oldStage < SectionStage.TERRAIN && newStage >= SectionStage.TERRAIN) {
            if (sectionY == this.terrainCursor) {
                this.terrainCursor = highestPendingBelow(chunk, sectionY);
            }
        }
        // 降档只有一条来路：旧格式条目缺 stage 时读回成 UNPROCESSED。记账两个方向都要成立，否则
        // 自检会把这类段报成失配，掩盖真的失配。
        if (oldStage >= SectionStage.SURFACE && newStage < SectionStage.SURFACE) {
            this.pendingSurface++;
        }
        if (oldStage >= SectionStage.DECORATED && newStage < SectionStage.DECORATED) {
            this.pendingDecorated++;
        }
        if (oldStage >= SectionStage.TERRAIN && newStage < SectionStage.TERRAIN
                && sectionY > this.terrainCursor) {
            this.terrainCursor = sectionY;
        }
    }

    private synchronized void remove(LevelChunk chunk, int sectionY, int stage) {
        if (!this.accounted.remove(sectionY)) {
            return;
        }
        if (stage < SectionStage.SURFACE) {
            this.pendingSurface--;
        }
        if (stage < SectionStage.DECORATED) {
            this.pendingDecorated--;
        }
        if (sectionY == this.terrainCursor) {
            this.terrainCursor = highestPendingBelow(chunk, sectionY);
        }
    }

    /**
     * limit 之下仍欠地形的最高段号，空集取 {@link #NO_CURSOR}。
     *
     * <p>两个来源都要看：段容器，以及只有档位没有条目的段，后者出现在非噪声生成器的维度里。
     * 只在游标那一段升档或移除时才调，属低频。
     */
    private static int highestPendingBelow(LevelChunk chunk, int limit) {
        int best = NO_CURSOR;
        for (Map.Entry<Integer, LevelChunkSection> e : ((WindowedChunk) chunk).windowedAllSections().entrySet()) {
            int sy = e.getKey();
            if (sy < limit && sy > best && SectionStage.getStage(chunk, sy) < SectionStage.TERRAIN) {
                best = sy;
            }
        }
        int[] upper = { best };
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (sy < limit && sy > upper[0] && stage < SectionStage.TERRAIN) {
                upper[0] = sy;
            }
        });
        return upper[0];
    }
}
