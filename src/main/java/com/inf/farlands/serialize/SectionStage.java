package com.inf.farlands.serialize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.util.map.Long2ObjectStripedMap;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

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
 * 推进点。BIOMES 在 BiomeFiller，TERRAIN 在 GenTask 的 fill 之后，SURFACE 在 SurfaceFiller，
 * CARVERS 在 CarverFiller，DECORATED 在 DecorationFiller 的收尾，LIGHTED 在
 * GenQueue.triggerLight 的主线程那一笔里由 promoteAllGenToLighted 升段。六处都经调度器的落档体写，
 * 落档体是档位与脏标记的唯一写入口。
 *
 * <p>光照会升的只有 DECORATED 一档，由 {@link #isAwaitingLight(int)} 一处给出：
 * promoteAllGenToLighted 与调度器第六行的判据都走它，避免两处各写一份而失同步。TERRAIN 与 SURFACE
 * 不在其中：它们的出路是同一个任务里的 surface 与 carve 两步，而光照回调是异步的，让回调把这两档升上去，
 * 那些段会被随后那两步放过，永不再被地表与雕刻处理。
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
     * 写档位。档位单调不减，降档请求被丢弃；读回走 {@link #restoreStage}。
     *
     * <p>读旧值与写新值在同一段的监视器内成对完成。少了这道同步，两个线程并发升同一段时会各自
     * 读到同一个旧档位，单调闸因此失效。
     */
    public static void setStage(LevelChunk chunk, int sectionY, int stage) {
        write(chunk, sectionY, stage, true);
    }

    /**
     * 读回专用的落档：按盘上档无条件写，绕过 {@link #setStage} 的单调闸。
     *
     * <p>唯一合法的降档来路是旧格式条目。fsa 条目缺 {@code stage} 键时解码成 {@code UNPROCESSED}，
     * 读回把它原样恢复，此时段可能已经在内存里被推到更高的档。其余任何路径都不得降档。
     */
    public static void restoreStage(LevelChunk chunk, int sectionY, int stage) {
        write(chunk, sectionY, stage, false);
    }

    /**
     * 落档本体。{@code monotone} 为真时拒绝降档。
     *
     * <p>闸的必要性来自执行线程：落档体已不再把写转投主线程，池线程直接写，于是两个池线程对同一段
     * 的两笔写可以乱序落地。一道迟到的低档写会把已经推进的段改回去，使该段重新变成欠账，而欠账会
     * 立刻再触发一次同样的工作，形成自持环。闸把这一类的第二笔写丢掉。
     */
    private static void write(LevelChunk chunk, int sectionY, int stage, boolean monotone) {
        ConcurrentHashMap<Integer, SectionState> m = open(chunk);
        if (m == null) {
            return;
        }
        // 读回收尾会把这类的空记录摘掉，见 dropReadMark。摘下与这里落档之间有一个微秒级的窗：
        // 本方法在监视器内复核 map 里仍是自己这张，不是就重取一次，重取会把一张新表挂回去。
        // 重试上限只为防活锁，实际只可能撞上一次读回收尾。
        for (int attempt = 0; attempt < 4; attempt++) {
            SectionState state = m.computeIfAbsent(sectionY, k -> new SectionState(UNPROCESSED));
            int old;
            synchronized (state) {
                if (m.get(sectionY) != state) {
                    continue;
                }
                old = state.stage();
                if (monotone && stage < old) {
                    return;
                }
                state.setStage(stage);
            }
            return;
        }
    }

    /** 该段的状态，不存在则建；身份为 0 或已注销返回 null。 */
    public static SectionState stateOf(LevelChunk chunk, int sectionY) {
        ConcurrentHashMap<Integer, SectionState> m = open(chunk);
        if (m == null) {
            return null;
        }
        return m.computeIfAbsent(sectionY, k -> new SectionState(UNPROCESSED));
    }

    /** 该段的状态，不存在返回 null，不建条目。 */
    public static SectionState stateOrNull(LevelChunk chunk, int sectionY) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return null;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        return m == null ? null : m.get(sectionY);
    }

    /** 标脏。身份为 0 的 chunk（ProtoChunk、客户端）没有状态，空转。 */
    public static void markDirty(LevelChunk chunk, int sectionY) {
        SectionState state = stateOf(chunk, sectionY);
        if (state != null) {
            state.markDirty();
        }
    }

    /** 读脏标记。身份为 0 或没有该段时返回假。 */
    public static boolean isDirty(LevelChunk chunk, int sectionY) {
        SectionState state = stateOrNull(chunk, sectionY);
        return state != null && state.isDirty();
    }

    /** 清脏标记。 */
    public static void clearDirty(LevelChunk chunk, int sectionY) {
        SectionState state = stateOrNull(chunk, sectionY);
        if (state != null) {
            state.clearDirty();
        }
    }

    /** 该 chunk 是否有段在读回。 */
    public static boolean anyReading(LevelChunk chunk) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return false;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        if (m == null) {
            return false;
        }
        for (SectionState state : m.values()) {
            if (state != null && state.isReading()) {
                return true;
            }
        }
        return false;
    }

    /** 某阶段是否已做。无 key 时按 UNPROCESSED。 */
    public static boolean isOrAfter(LevelChunk chunk, int sectionY, int stage) {
        return getStage(chunk, sectionY) >= stage;
    }

    /** 删除某 section 的状态。fsa 清理时状态已随 section 落盘，载体同步删除。 */
    public static void removeStage(LevelChunk chunk, int sectionY) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        if (m == null) {
            return;
        }
        m.remove(sectionY);
    }

    /**
     * chunk 卸载时清掉它的整条状态：先注销身份，再删状态表与两个一次性标志。
     *
     * <p>顺序是契约。先注销，在途任务随后的 {@link #setStage} 与写标志就都会空转，不会为同坐标的新
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
     * 装饰会升的唯一一档：CARVERS。停在 CARVERS 的段等着装饰，装饰收尾把它们升到 DECORATED。
     * 与 {@link #isAwaitingLight(int)} 对称，两处都只认一档。
     */
    public static boolean isAwaitingDecoration(int stage) {
        return stage == CARVERS;
    }

    /** 该 chunk 是否有恰好停在 {@code stage} 的段。档位表口径，不看容器。 */
    public static boolean anyAt(LevelChunk chunk, int stage) {
        boolean[] found = { false };
        forEachStage(chunk, (sy, s) -> {
            if (s == stage) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 该段是否已到终态，即 {@code stage >= LIGHTED}。
     *
     * <p>fsa 与下发共用这一条：只有终态的段可以编码落盘，也只有终态的段可以下发；低于它的都是中间态。
     * 写门在 {@code ChunkReadiness} 里也读它，因为「数据已定」与「可以落盘」在这个模型里是同一个时刻。
     *
     * <p>不要与 {@link #isBelowLighted(int)} 互相当取反用：那个只覆盖 TERRAIN 到 LIGHTED 的中间态，
     * {@code UNPROCESSED} 与 {@code BIOMES} 既不是终态也不是中间态。
     */
    public static boolean isTerminal(int stage) {
        return stage >= LIGHTED;
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
        List<Integer> awaiting = new ArrayList<>();
        for (Map.Entry<Integer, SectionState> e : m.entrySet()) {
            SectionState state = e.getValue();
            if (state != null && isAwaitingLight(state.stage())) {
                awaiting.add(e.getKey());
            }
        }
        if (awaiting.isEmpty()) {
            return;
        }
        int[] sections = new int[awaiting.size()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = awaiting.get(i);
        }
        // 升格改由落档体执行：档位与记账只能由主线程成对写，本方法因此可被池线程调用。
        com.inf.farlands.terrain.pipeline.Scheduler.stage(chunk, sections, LIGHTED);
        StageMetrics.lightedSections(sections.length);
        StageMetrics.lightedChunk();
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
     * <p>判据是扫段表，与 {@link #isBelowLighted} 同源：它覆盖段容器的并集，含只有档位没有条目的段。
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

    /**
     * 该 chunk 的段号并集：段容器的键并上档位表的键，按可玩段界过滤后升序。
     *
     * <p>两个面必须合起来用。档位可以落在容器里没有条目的段上：非噪声生成器的维度里 fill 直接早退，
     * 一个段都没物化，而生成任务照样把整条 band 落到 {@code TERRAIN}；读回窗口里那些盘上没有数据的
     * 段，也会先建出 {@code UNPROCESSED} 的条目再等到收尾才摘。只遍历容器会让前一类段永远停在
     * {@code TERRAIN}，而准入按档位判「还有欠账」，于是该 chunk 被无限放行、作业次次空转。
     *
     * <p>噪声生成器的维度里，fill 会把 Y 循环经过的每个段都 {@code getSection} 出来，
     * 所以「全空气的段没有容器条目」在这条路径上不成立，物化过的空段也是段。
     */
    public static int[] sectionYs(LevelChunk chunk) {
        IntOpenHashSet set = new IntOpenHashSet();
        for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            if (sy != null && WorldBounds.inSection(sy)) {
                set.add(sy.intValue());
            }
        }
        forEachStage(chunk, (sy, stage) -> {
            if (WorldBounds.inSection(sy)) {
                set.add(sy.intValue());
            }
        });
        int[] years = set.toIntArray();
        Arrays.sort(years);
        return years;
    }

    /**
     * 读回收尾：把「标记建出来、但既没落档也没物化」的空条目摘掉。
     *
     * <p>判据三条缺一不可的原因：档位仍是 {@code UNPROCESSED}，有档位就是有工作，空段也算；
     * 容器里没有这个段，缺段即空气，但物化过的空段仍然是段；此刻不在读，unmark 之后窗口可能又滑回来
     * 重新标记。
     *
     * <p>摘掉不丢任何记账：本类只存档位与两个标志，档位表里没有条目等价于 {@code UNPROCESSED}。
     *
     * <p>与落档体的竞态：{@link #write} 在监视器内复核条目仍是自己那张，所以在这里的监视器内摘下
     * 不会让一笔正在落的档写到已经脱离表的对象上。
     */
    public static void dropReadMark(LevelChunk chunk, int sectionY) {
        long identity = liveIdentity(chunk);
        if (identity == 0L) {
            return;
        }
        ConcurrentHashMap<Integer, SectionState> m = STAGES.get(identity);
        if (m == null) {
            return;
        }
        SectionState state = m.get(sectionY);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.isReading() || state.stage() != UNPROCESSED) {
                return;
            }
            if (((WindowedChunk) chunk).windowedAllSections().containsKey(sectionY)) {
                return;
            }
            m.remove(sectionY, state);
        }
    }
}
