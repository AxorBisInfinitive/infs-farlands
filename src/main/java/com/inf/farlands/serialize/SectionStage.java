package com.inf.farlands.serialize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.util.map.Long2ObjectStripedMap;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * per-section 生成阶段，fsa 的持久化字段之一，写进 fsa 条目并在读回时恢复。
 *
 * 取值顺序单值，即依赖顺序，比较用 {@code >=} ：
 *   0 UNPROCESSED  未处理
 *   1 BIOMES       群系已填
 *   2 TERRAIN      该段已 fill
 *   3 SURFACE      地表已应用
 *   4 CARVERS      雕刻已完成，地物未放
 *   5 DECORATED    该 chunk 作为中心的那一遍装饰已跑完
 *   6 LIGHTED      光照已完成
 *
 * <p>不变式：装饰先于点亮，即 LIGHTED 蕴含 DECORATED。fsa 只写 stage >= LIGHTED 的段，所以
 * DECORATED 只活在内存里，磁盘上不会出现它。
 *
 * <p>载体：**身份**到 sectionY 到 {@link SectionState} 的分段 map。键是身份而不是坐标，理由有
 * 两条：一是同坐标的前一代与后一代是两份状态，按坐标共键会让新一代继承旧账；二是卸载时的那次清
 * 会与在途任务的写口竞争，键是坐标时清完之后任务还能把旧代的档位写进新一代，键是身份时那道写口
 * 因为身份已注销而直接空转。身份由 {@link CounterIdentities} 分配与注销，只给服务端的 LevelChunk。
 *
 * <p>派生记账：档位变化与物化都同步 {@link ChunkCounters}，后者供调度器的依赖判据 O(1) 取值。
 * 记账一律在段状态表的锁放开之后进行，两个写方法都不在 ConcurrentHashMap 的回调里调它。
 *
 * 推进点。BIOMES 在 BiomeFiller，TERRAIN 在 GenTask 的 fill 之后，SURFACE 在 SurfaceFiller，
 * CARVERS 在 CarverFiller，DECORATED 在 DecorationFiller 的收尾，LIGHTED 在
 * GenQueue.triggerLight 的主线程那一笔里由 promoteAllGenToLighted 升段。
 *
 * <p>光照会升的只有 DECORATED 一档，由 {@link #isAwaitingLight(int)} 一处给出：
 * promoteAllGenToLighted、hasAwaitingLight 与 GenTask 的补触发判据都走它，避免三处各写一份而
 * 失同步。TERRAIN 与 SURFACE 不在其中：它们的出路是同一个任务里的 surface 与 carve 两步，而光照
 * 回调是异步的，让回调把这两档升上去，那些段会被随后那两步放过，永不再被地表与雕刻处理。
 *
 * 生命周期。chunk 卸载时由 SectionLifecycle.flushChunk 的每条出口调用 {@link #clear(LevelChunk)}，
 * 内存有界。
 * 并发。写点在主线程、genPool、backgroundExecutor 或 lightPool，值容器是 ConcurrentHashMap，
 * 档位字段是 volatile，因为编码池的 drainEncode 会读它。
 */
public final class SectionStage {

    public static final int UNPROCESSED = 0;
    public static final int BIOMES = 1;
    public static final int TERRAIN = 2;
    public static final int SURFACE = 3;
    public static final int CARVERS = 4;
    public static final int DECORATED = 5;
    public static final int LIGHTED = 6;

    private static final Long2ObjectStripedMap<ConcurrentHashMap<Integer, SectionState>> STAGES =
            new Long2ObjectStripedMap<>(1 << 12);

    private SectionStage() {
    }

    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof CounterIdentity identity ? identity.counterId() : 0L;
    }

    /** 活着的身份，死的返回 0，读写整体空转。 */
    private static long liveIdentity(LevelChunk chunk) {
        long identity = identityOf(chunk);
        return CounterIdentities.isLive(identity) ? identity : 0L;
    }

    /**
     * 取或建该身份的段状态表。建完再判一次活：判活与建表之间可能刚好被注销，那张刚建的空表要
     * 立刻收回，否则它会成为没人再清的残留。
     */
    private static ConcurrentHashMap<Integer, SectionState> open(LevelChunk chunk) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return null;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.computeIfAbsent(identity, k -> new ConcurrentHashMap<>());
        if (!CounterIdentities.isLive(identity)) {
            STAGES.remove(identity);
            return null;
        }
        return m;
    }

    public static int getStage(LevelChunk chunk, int sectionY) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return UNPROCESSED;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        if (m == null) {
            return UNPROCESSED;
        }
        SectionState state = m.get(sectionY);
        return state == null ? UNPROCESSED : state.stage();
    }

    /**
     * 写档位并同步派生记账。
     *
     * <p>读旧值、写新值、记账三步在同一段的监视器内成对完成。少了这道同步，两个线程并发升同一段
     * 时会各自读到同一个旧档位，跨越同一道阈值的减计数各做一次，计数被多减。
     *
     * <p>锁序：本方法持段监视器，随后进记账的实例锁；记账侧只读 {@link #getStage} 与段容器，
     * 不反向取段监视器，因此不成环。
     */
    public static void setStage(LevelChunk chunk, int sectionY, int stage) {
        ConcurrentHashMap<Integer, SectionState> m = open(chunk);
        if (m == null) {
            return;
        }
        SectionState state = m.computeIfAbsent(sectionY, k -> new SectionState(UNPROCESSED));
        int old;
        synchronized (state) {
            old = state.stage();
            state.setStage(stage);
        }
        ChunkCounters.onStageChange(chunk, sectionY, old, stage);
    }

    /** 某阶段是否已做。无 key 时按 UNPROCESSED。 */
    public static boolean isOrAfter(LevelChunk chunk, int sectionY, int stage) {
        return getStage(chunk, sectionY) >= stage;
    }

    /** 删除某 section 的状态。fsa 清理时状态已随 section 落盘，载体同步删除，记账反向减。 */
    public static void removeStage(LevelChunk chunk, int sectionY) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        if (m == null) {
            return;
        }
        SectionState removed = m.remove(sectionY);
        if (removed != null) {
            synchronized (removed) {
                ChunkCounters.onRemove(chunk, sectionY, removed.stage());
            }
        }
    }

    /**
     * chunk 卸载时清掉它的整条状态：先注销身份，再删状态表与记账。
     *
     * <p>顺序是契约。先注销，在途任务随后的 {@link #setStage} 与记账就都会空转，不会为同坐标的新
     * 一代把旧状态建回来；先删表的话，中间那一段窗口里任务还能建回来，而那时身份已死、再没人清它。
     */
    public static void clear(LevelChunk chunk) {
        long identity = identityOf(chunk);
        if (identity == 0L) {
            return;
        }
        CounterIdentities.unregister(identity);
        STAGES.remove(identity);
        ChunkCounters.clear(chunk);
    }

    /**
     * 光照会升的唯一一档：DECORATED。一次光照跑完，这一档的段升到 LIGHTED。
     *
     * <p>TERRAIN 与 SURFACE 不在其中：它们的出路是同一个生成任务里的 surface 与 carve 两步，而光照
     * 回调是异步的，可能与那个任务并行。让回调把这两档升上去，那些段就会被随后那两步放过，永远不再
     * 被地表与雕刻处理，而档位已经是 LIGHTED，fsa 还会按已完成写盘。
     *
     * <p>CARVERS 也不在其中：它的出路是装饰；把它算进来会让回调升完段后仍判为还有活，无限续接下一轮。
     */
    public static boolean isAwaitingLight(int stage) {
        return stage == DECORATED;
    }

    /**
     * 光照完成回调：该 chunk 全部在 {@link #isAwaitingLight(int)} 里的段，即停在 DECORATED 的段，
     * 升到 LIGHTED。逐段判前置就是判「这一段已装饰」，两侧同源，所以不会漏判也不会多升。
     *
     * <p>实现是先收集再升，不在 replaceAll 的回调里记账：那是持着段状态表桶锁的时刻，而记账要扫
     * 段容器。第二遍对每段重新判一次前置，与旧写法逐条重估的语义一致。
     *
     * <p>调用点只有一个，且在 triggerLight 的主线程那一笔里，先于 persistChunkDirty：编码侧只放行
     * 不低于 LIGHTED 的段，先入队后升格会让那一批在编码时被拒。
     */
    public static void promoteAllGenToLighted(LevelChunk chunk) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        if (m == null) {
            return;
        }
        List<int[]> awaiting = new ArrayList<>();
        for (Map.Entry<Integer, SectionState> e : m.entrySet()) {
            SectionState state = e.getValue();
            if (state != null && isAwaitingLight(state.stage())) {
                awaiting.add(new int[] { e.getKey(), state.stage() });
            }
        }
        int promoted = 0;
        for (int[] awaited : awaiting) {
            SectionState state = m.get(awaited[0]);
            if (state == null) {
                continue;
            }
            synchronized (state) {
                if (!isAwaitingLight(state.stage())) {
                    continue;
                }
                int old = state.stage();
                state.setStage(LIGHTED);
                ChunkCounters.onStageChange(chunk, awaited[0], old, LIGHTED);
            }
            promoted++;
        }
        if (promoted > 0) {
            StageMetrics.lightedSections(promoted);
            StageMetrics.lightedChunk();
        }
    }

    /**
     * 该 chunk 是否还有光照能升、但还没升的 section，即停在 DECORATED 的段。光照完成后再检查，
     * 驱动下一批。
     *
     * <p>判据由 {@link ChunkCounters#atDecorated} 的计数给出，与 {@link #promoteAllGenToLighted} 的
     * 匹配面同源：两处都只认 DECORATED 一档。放宽到全体 belowLighted 会把 CARVERS 也算进来，
     * 回调因此永远为真。
     *
     * <p>不再遍历段状态表：那次遍历的判据与升格同源却各写一份，正是两者失同步的来源；计数由
     * {@link ChunkCounters} 在档位写点维护，读一次即得。
     */
    public static boolean hasAwaitingLight(LevelChunk chunk) {
        return ChunkCounters.atDecorated(chunk) > 0;
    }

    /** 段是否做过但没点亮，即 stage 落在 TERRAIN 到 LIGHTED 之间，含 DECORATED。 */
    public static boolean isBelowLighted(int stage) {
        return stage >= TERRAIN && stage < LIGHTED;
    }

    /** 停服时清空全部 chunk 的阶段表与身份注册表。键只含进程内身份，不清会让下一个世界继承旧状态。 */
    public static void clearAll() {
        STAGES.clear();
        ChunkCounters.clearAll();
        CounterIdentities.clearAll();
        // 吞吐计数与阶段表同生命周期，一起清，防跨世界把上一份累计串进来。
        StageMetrics.clearWorldState();
    }

    /**
     * 该 chunk 是否有做过但没点亮的段。
     *
     * <p>扫描路径也要带上这条判据：一个 stage 停在 CARVERS 的 chunk 没有任何 pending 步骤，
     * 光照失败一次就再没有下一次，它的段也永远不会写盘。
     */
    public static boolean hasBelowLighted(LevelChunk chunk) {
        boolean[] found = { false };
        forEachStage(chunk, (sy, stage) -> {
            if (isBelowLighted(stage)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** 遍历该 chunk 的 section 状态。无 key 或身份已死时什么都不做。 */
    public static void forEachStage(LevelChunk chunk, BiConsumer<Integer, Integer> consumer) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        if (m != null) {
            m.forEach((sy, state) -> consumer.accept(sy, state == null ? UNPROCESSED : state.stage()));
        }
    }
}
