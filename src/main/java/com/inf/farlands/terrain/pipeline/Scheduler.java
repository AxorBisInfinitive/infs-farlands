package com.inf.farlands.terrain.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.debug.CandidateProbe;
import com.inf.farlands.debug.DispatchProbe;
import com.inf.farlands.debug.StageWriteProbe;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.PlayerSnapshot;
import com.inf.farlands.util.world.WorldBounds;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Beardifier;

/**
 * 调度器：候选表、力面、准入、派发、第七行与落档体。主线程单点。
 *
 * <p>候选表按身份记，落档体的扇出、入队入口、装饰与结构相的信号都往这里 submit。每 tick 一次
 * {@link #tick()}：按距离排序、逐候选准入，过了就交出去，装饰相与生成相各判自己的门，第七行成立就
 * 直接补 FULL。
 *
 * <p>力面：一个面是「某个维度里、半径 radius 的方阵、段范围 [minSection, maxSection]」，由出生区
 * 预加载与 Chunky 登记。准入的竖直判据读玩家窗口与力面的并集，因为这两个面都没有玩家。
 *
 * <p>候选的段范围只有一个来源，见 {@link #sectionsFor}：准入的欠账判据与 {@code GenTask} 的收集读
 * 同一份数组。两处各写一份来源会让准入放行而任务收不到段，那是每 tick 空转的直接来源。
 *
 * <p>准入被拒分两类：{@code WINDOW} 与 {@code FACE} 不重投，窗口滑动与力面登记会重新提交；其余
 * 暂时性原因按 {@link #RETRY_BACKOFF_MILLIS} 退避重投，次数上限 {@link #RETRY_MAX_ATTEMPTS}。
 * 重投用墙钟而不是 tick 计数：预加载与建世界期没有 tick，按 tick 记的退避永远不会到期。
 */
public final class Scheduler {

    /**
     * 一个驱动面。
     *
     * @param dimension  所属维度
     * @param centerX    中心 chunk 的 x
     * @param centerZ    中心 chunk 的 z
     * @param radius     驱动半径，chunk 数
     * @param minSection 段范围下界
     * @param maxSection 段范围上界
     */
    public record Face(ResourceKey<Level> dimension, int centerX, int centerZ, int radius, int minSection,
            int maxSection) {
    }

    private static final Map<ResourceKey<Level>, List<Face>> FACES = new ConcurrentHashMap<>();

    private Scheduler() {
    }

    /** 登记一个面。主线程调用。 */
    public static void register(Face face) {
        FACES.computeIfAbsent(face.dimension(), k -> new ArrayList<>()).add(face);
    }

    /** 撤销一个面。主线程调用。 */
    public static void unregister(Face face) {
        List<Face> list = FACES.get(face.dimension());
        if (list != null) {
            list.remove(face);
        }
    }

    /** 当前面数，供 `FLSCHED` 读数。 */
    public static int faces() {
        int n = 0;
        for (List<Face> list : FACES.values()) {
            n += list.size();
        }
        return n;
    }

    /** 停服清空。 */
    public static void clearWorldState() {
        FACES.clear();
        CANDIDATES.clear();
        REJECT_LOGGED.clear();
        FULL_LOGGED.clear();
        SEQ.set(0);
    }

    // ---- 候选与准入 ----

    /** 一个待办的 chunk。preloadSections 非空表示调用方给了显式范围，与面、窗口并集合并使用。 */
    private static final class Candidate {

        final LevelChunk chunk;
        final int[] preloadSections;
        final long seq;
        /** 被暂时性原因拒绝的次数，供退避取值与上限判定。 */
        int attempts;
        /** 最早可再次尝试的墙钟时刻。 */
        long dueAtMillis;

        Candidate(LevelChunk chunk, int[] preloadSections, long seq) {
            this.chunk = chunk;
            this.preloadSections = preloadSections;
            this.seq = seq;
        }
    }

    private static final Map<Long, Candidate> CANDIDATES = new ConcurrentHashMap<>();

    /** 诊断行的限流：同一 chunk 同一原因只打一次。 */
    private static final Set<Long> REJECT_LOGGED = ConcurrentHashMap.newKeySet();

    /** 第七行求值行的限流：同一 chunk 只打一次。 */
    private static final Set<Long> FULL_LOGGED = ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** 退避梯度，单位毫秒。超出长度后取最后一档。 */
    private static final long[] RETRY_BACKOFF_MILLIS = { 2L, 8L, 200L };

    /** 同一候选被暂时性原因拒绝的上限，超过即放弃，等下一次显式提交。 */
    private static final int RETRY_MAX_ATTEMPTS = 64;

    private static final int[] EMPTY_SECTIONS = new int[0];

    /** 该 chunk 的记账身份，0 表示客户端或 ProtoChunk。 */
    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof com.inf.farlands.serialize.CounterIdentity identity ? identity.counterId() : 0L;
    }

    /** 登记一个待办 chunk，段范围由 {@link #sectionsFor} 现算。 */
    public static void submit(LevelChunk chunk) {
        submit(chunk, null);
    }

    /**
     * 候选表里是否还有待派发的 chunk。
     *
     * <p>只给预 tick 期的泵用：那一段没有 tick 循环，泵要判断「管道是否已经清空」才能退出。
     * 有候选不代表有进展，退避中的候选也在这里；调用方要另配时间上限。
     */
    public static boolean hasPendingCandidates() {
        return !CANDIDATES.isEmpty();
    }

    /** 该 chunk 是否还在候选表里。诊断用，主线程。 */
    public static boolean hasCandidate(LevelChunk chunk) {
        long key = identityOf(chunk);
        return key != 0L && CANDIDATES.containsKey(key);
    }

    /** 派发重入闸：落档体的扇出会在派发内 submit，不能再递归进来。 */
    private static final ThreadLocal<Boolean> TICKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 登记一个待办 chunk，显式范围只作为 {@link #sectionsFor} 的一支。入队即派发。 */
    public static void submit(LevelChunk chunk, int[] preloadSections) {
        long key = identityOf(chunk);
        if (key == 0L) {
            return;
        }
        CANDIDATES.put(key, new Candidate(chunk, preloadSections, SEQ.incrementAndGet()));
        // 入队即派发：createLevels 里 setInitialSpawn 的阻塞取数发生在 tick 之前，只登记会死锁。
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (SectionIO.isMainThread(level)) {
            if (!TICKING.get()) {
                tick();
            }
        } else {
            SectionIO.runOnMainThread(Scheduler::tick, level);
        }
    }

    /**
     * 延后一段时间再派发该 chunk：登记候选并把最早可尝试时刻推到 {@code delayMillis} 之后。
     *
     * <p>用途是「此刻办不成、但还会再试」的失败出口，例如装饰取不到光照域锁。直接 {@code submit}
     * 会把每一次失败立刻接成下一轮尝试，主线程与池子都烧在同一个 chunk 上。延迟的计时者是墙钟而不是
     * tick：建世界期与预加载期都没有 tick，按 tick 记的延迟永远不会到期。
     *
     * <p>不立即派发，因为还没有到期，派发只会跳过它。
     */
    public static void submitLater(LevelChunk chunk, long delayMillis) {
        if (delayMillis <= 0L) {
            submit(chunk);
            return;
        }
        long key = identityOf(chunk);
        if (key == 0L) {
            return;
        }
        Candidate candidate = new Candidate(chunk, null, SEQ.incrementAndGet());
        candidate.dueAtMillis = System.currentTimeMillis() + delayMillis;
        CANDIDATES.put(key, candidate);
    }

    /** 每 tick 一次：按距离排序逐个准入，过了就交出去。 */
    public static void tick() {
        if (TICKING.get()) {
            return;
        }
        TICKING.set(Boolean.TRUE);
        try {
            dispatch();
        } finally {
            TICKING.set(Boolean.FALSE);
        }
    }

    private static void dispatch() {
        if (CANDIDATES.isEmpty()) {
            return;
        }
        int budget = FarlandsConfig.maxGenTasksPerTick;
        List<Map.Entry<Long, Candidate>> snapshot = new ArrayList<>(CANDIDATES.entrySet());
        snapshot.sort(Comparator.comparingLong(e -> distanceOf(e.getValue())));
        Map<ServerLevel, SectionLifecycle.ChunkCursor> cursors = new HashMap<>();
        for (Map.Entry<Long, Candidate> entry : snapshot) {
            if (budget <= 0) {
                break;
            }
            long key = entry.getKey();
            Candidate candidate = entry.getValue();
            LevelChunk chunk = candidate.chunk;
            // 同坐标的前一代已交卸载：身份与键不再相符，条目是死引用，清掉。
            if (identityOf(chunk) != key) {
                CANDIDATES.remove(key, candidate);
                CandidateProbe.left(chunk, "STALE", 1, true);
                continue;
            }
            long now = System.currentTimeMillis();
            if (now < candidate.dueAtMillis) {
                continue;
            }
            // 条件删除：快照之后新提交的同键候选不能被旧候选删掉。
            if (!CANDIDATES.remove(key, candidate)) {
                continue;
            }
            if (!(chunk.getLevel() instanceof ServerLevel level)) {
                CandidateProbe.left(chunk, "NONLEVEL", 1, true);
                continue;
            }
            ChunkPos pos = chunk.getPos();
            if (!WorldBounds.inChunkRange(pos.x(), pos.z())) {
                SchedulerMetrics.rejectFace();
                logReject(level, chunk, "FACE", "");
                CandidateProbe.left(chunk, "FACE", 1, true);
                continue;
            }
            SectionLifecycle.ChunkCursor cursor = cursors.computeIfAbsent(level, SectionLifecycle::cursor);
            int ticketLevel = cursor.ticketLevelAt(pos.x(), pos.z());
            int allowed = DependencyTable.allowedStage(ticketLevel);
            if (allowed < SectionStage.TERRAIN) {
                SchedulerMetrics.rejectTicket();
                logReject(level, chunk, "TICKET", "ticket=" + ticketLevel + " allowed=" + allowed);
                requeue(key, candidate, "TICKET");
                continue;
            }
            if (!GenQueue.isNearPlayer(chunk)) {
                SchedulerMetrics.rejectWindow();
                logReject(level, chunk, "WINDOW", "");
                CandidateProbe.left(chunk, "WINDOW", 1, true);
                continue;
            }
            int[] sections = sectionsFor(level, chunk, candidate.preloadSections);
            boolean wrDone = ChunkCounters.isWindowReadDone(chunk);
            // 第七行：本候选范围内每段都已点亮，且窗口面读过盘。范围为空不算完成：那是「没有可做的
            // 段」，不是「做完了」。FULL 只对「有人等」的 chunk 承诺，所以这一行排在面与窗口之后。
            if (wrDone && sections.length > 0
                    && DependencyTable.pendingBelowIn(chunk, sections, SectionStage.LIGHTED) == 0) {
                logFull(level, chunk);
                ChunkReadiness.completeFull(chunk);
                CandidateProbe.left(chunk, "FULL", 1, false);
                continue;
            }
            // 生成支：范围内还有低于 CARVERS 的段。装饰支：本 chunk 已有段停在 CARVERS。两支分开判，
            // 因为段全到 CARVERS 之后生成侧无活可做，而装饰恰恰从那一刻开始。
            boolean gen = sections.length > 0
                    && DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS) > 0;
            boolean deco = anyStage(chunk, SectionStage.CARVERS);
            if (!gen && !deco) {
                SchedulerMetrics.blocked();
                logReject(level, chunk, "WORK", "");
                requeue(key, candidate, "WORK");
                continue;
            }
            if (deco && DecorationFiller.trySubmitDecoration(chunk)) {
                SchedulerMetrics.emitted();
                budget--;
                continue;
            }
            if (!gen) {
                SchedulerMetrics.blocked();
                logReject(level, chunk, "DECO", "");
                requeue(key, candidate, "DECO");
                continue;
            }
            // C 依赖：适配数据就位。未建时若 ±8 的壳齐就现场建引用相与 Beardifier，建完凭下一次提交重试。
            if (!((ChunkBeardifier) chunk).hasBeardifier()) {
                boolean enabled = StructureDriver.structuresEnabled(level);
                boolean inRange = StructureDriver.inStructureRange(chunk);
                boolean refs = enabled && inRange && StructureDriver.referencesBuildable(level, chunk);
                if (refs) {
                    StructureDriver.buildReferences(level, chunk);
                    ((ChunkBeardifier) chunk).setBeardifier(
                            Beardifier.forStructuresInChunk(level.structureManager(), chunk.getPos()));
                    submit(chunk);
                } else {
                    SchedulerMetrics.rejectReady();
                    logReject(level, chunk, "READY", "structures=" + enabled + " inRange=" + inRange);
                    requeue(key, candidate, "READY");
                }
                continue;
            }
            SchedulerMetrics.emitted();
            budget--;
            CandidateProbe.left(chunk, "GEN", 1, false);
            // 派发序号：作业入口那一行会带上同一个值，两边按序号对齐就能量出这一格被重派的节奏。
            long dispatchSeq = DispatchProbe.next(pos,
                    DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS), sections.length);
            GenQueue.enqueueTask(chunk, sections, dispatchSeq);
        }
    }

    /**
     * 本候选该处理的段：显式范围、所有覆盖该格的面、该维度的窗口并集的并集，再按可玩段界与在读状态
     * 过滤，升序去重。
     *
     * <p>准入的欠账判据与 {@code GenTask} 的收集必须读这一份。三支来源在旧写法里各自成判据，而收集侧
     * 只认窗口，于是面带里的段被准入当成欠账、任务又收不到，每 tick 空转一次。
     *
     * <p>过滤用 {@link WorldBounds#inSection} 而不是可表示段界：{@code AbstractTerrainFiller} 用
     * {@code minSection*16} 建 {@code NoiseSettings}，可表示界两端乘 16 已越过 int。
     *
     * <p>升序是前提：{@code GenTask.collectSegments} 只按 {@code sy == curMax+1} 判连续，乱序输入会把
     * 每次收集退化成一堆单段，重复输入会把同一段填两次。
     */
    /**
     * 不带显式范围的那一支：只取覆盖该格的面与窗口并集。事件点与退出读数用这条。
     *
     * <p>与带 {@code explicit} 的重载同一份实现。事件点手上没有候选，取到的范围与预加载期的显式范围
     * 等价：那时窗口为空，面就是范围。
     */
    public static int[] sectionsFor(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            return sectionsFor(level, chunk, null);
        }
        return EMPTY_SECTIONS;
    }

    static int[] sectionsFor(ServerLevel level, LevelChunk chunk, int[] explicit) {
        IntOpenHashSet set = new IntOpenHashSet();
        if (explicit != null) {
            for (int sy : explicit) {
                set.add(sy);
            }
        }
        List<Face> faces = FACES.get(level.dimension());
        if (faces != null) {
            ChunkPos pos = chunk.getPos();
            for (Face face : faces) {
                if (Math.max(Math.abs(face.centerX() - pos.x()), Math.abs(face.centerZ() - pos.z())) <= face.radius()) {
                    for (int sy = face.minSection(); sy <= face.maxSection(); sy++) {
                        set.add(sy);
                    }
                }
            }
        }
        EntitySectionWindow.forEachSectionInAnyWindow(level.dimension(), set::add);
        if (set.isEmpty()) {
            return EMPTY_SECTIONS;
        }
        int[] sorted = set.toIntArray();
        Arrays.sort(sorted);
        int n = 0;
        for (int i = 0; i < sorted.length; i++) {
            int sy = sorted[i];
            if (!WorldBounds.inSection(sy)) {
                continue;
            }
            // 读回会整体替换段对象，fill 与它重叠时写进旧对象的内容被丢弃，而高度图已被改过。
            if (SectionIO.isReading(chunk, sy)) {
                continue;
            }
            sorted[n++] = sy;
        }
        return n == sorted.length ? sorted : Arrays.copyOf(sorted, n);
    }

    /** 该 chunk 是否有恰好停在 stage 的段。装饰的判据，与依赖表的 CARVERS 行同源。 */
    private static boolean anyStage(LevelChunk chunk, int stage) {
        boolean[] found = { false };
        SectionStage.forEachStage(chunk, (sy, s) -> {
            if (s == stage) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 暂时性拒绝的重投：带退避，次数到顶即放弃，等下一次显式提交。
     *
     * <p>到顶那一次是静默丢活：候选离开表，除非法提交再没人管它。{@code exit} 只作读数，标出是被哪道
     * 门耗尽的。
     */
    private static void requeue(long key, Candidate candidate, String exit) {
        if (candidate.attempts >= RETRY_MAX_ATTEMPTS) {
            CandidateProbe.exhausted(candidate.chunk, exit, candidate.attempts);
            return;
        }
        candidate.attempts++;
        long backoff = RETRY_BACKOFF_MILLIS[Math.min(candidate.attempts - 1, RETRY_BACKOFF_MILLIS.length - 1)];
        candidate.dueAtMillis = System.currentTimeMillis() + backoff;
        CANDIDATES.put(key, candidate);
        SchedulerMetrics.repost();
    }

    /** 距离用快照，避免逐候选遍历玩家。 */
    private static long distanceOf(Candidate candidate) {
        LevelChunk chunk = candidate.chunk;
        if (chunk.getLevel() instanceof ServerLevel level) {
            return PlayerSnapshot.nearestDistance(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
        }
        return Long.MAX_VALUE;
    }

    /** 第七行求值行的读数，同一 chunk 只打一次。 */
    private static void logFull(ServerLevel level, LevelChunk chunk) {
        long key = chunk.getPos().pack();
        if (!FarlandsConfig.logScheduler || !FULL_LOGGED.add(key)) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLFULL evald dim={} chunk={},{} wrDone={} claimed={} holder={}",
                level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(),
                ChunkCounters.isWindowReadDone(chunk), ChunkCounters.isFullDone(chunk),
                SectionLifecycle.latestHolder(level, chunk.getPos().x(), chunk.getPos().z()) != null);
    }

    /** 诊断行：准入被拒的原因与四个前件。同一 chunk 同一原因只打一次。 */
    private static void logReject(ServerLevel level, LevelChunk chunk, String reason, String extra) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        // 键留 8 位给原因：只留 3 位时多个原因会撞在同一位上，先打的那条把后打的吞掉。
        long key = (chunk.getPos().pack() << 8) | (reason.hashCode() & 0xFFL);
        if (!REJECT_LOGGED.add(key)) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLBLOCK reject reason={} dim={} chunk={},{} {} below={} wrDone={} beard={} stages={}",
                reason, level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(), extra,
                SectionStage.hasBelowLighted(chunk), ChunkCounters.isWindowReadDone(chunk),
                ((ChunkBeardifier) chunk).hasBeardifier(), stageHistogram(chunk));
    }

    /** 该 chunk 的段位直方图，按档位计数。 */
    private static String stageHistogram(LevelChunk chunk) {
        int[] counts = new int[SectionStage.LIGHTED + 1];
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage >= 0 && stage < counts.length) {
                counts[stage]++;
            }
        });
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(counts[i]);
        }
        return sb.append(']').toString();
    }

    /** 该 chunk 落在哪个面的方阵内，返回该面的段带 `{min, max}`；不在任何面内返回 null。 */
    public static int[] sectionRangeAt(ServerLevel level, ChunkPos pos) {
        List<Face> faces = FACES.get(level.dimension());
        if (faces == null) {
            return null;
        }
        for (Face face : faces) {
            if (Math.max(Math.abs(face.centerX() - pos.x()), Math.abs(face.centerZ() - pos.z())) <= face.radius()) {
                return new int[] { face.minSection(), face.maxSection() };
            }
        }
        return null;
    }

    /** 该 chunk 是否落在某个面的方阵内。供 `isNearPlayer` 复合。 */
    static boolean inAnyFace(ServerLevel level, ChunkPos pos) {
        List<Face> faces = FACES.get(level.dimension());
        if (faces == null) {
            return false;
        }
        for (Face face : faces) {
            if (Math.max(Math.abs(face.centerX() - pos.x()), Math.abs(face.centerZ() - pos.z())) <= face.radius()) {
                return true;
            }
        }
        return false;
    }

    // ---- 落档体：档位与脏标记的唯一写入口 ----

    /**
     * 把一段集合的档位推到目标档。主线程上内联执行；池线程上 marshal 回主线程。
     *
     * <p>档位与脏标记只能由主线程写：`flags` 的读改写在池线程上会丢写，而 `stage` 的记账要与它成对。
     */
    public static void stage(LevelChunk chunk, List<Integer> sections, int targetStage) {
        int[] arr = new int[sections.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = sections.get(i);
        }
        stage(chunk, arr, targetStage);
    }

    /**
     * 把一段数组的档位推到目标档，语义同 {@link #stage(LevelChunk, List, int)}。
     *
     * <p>不转投主线程。档位所在的 {@link com.inf.farlands.serialize.SectionState} 字段是 volatile，
     * 读改写在段监视器内，派生记账的增减也在实例锁内，池线程直接写因此是安全的；而转投的代价是读者
     * 看到的是队列尾巴而不是写入结果，判据据此反复判定同一段欠账，重复投写、队列越积越长。
     *
     * <p>脏标记仍走 {@link #dirty} 的转投：它与编码池的清除之间需要串行，转投那一步是那条串行的载体。
     */
    public static void stage(LevelChunk chunk, int[] sections, int targetStage) {
        if (sections.length == 0) {
            return;
        }
        // 诊断：请求侧的段数，与真正走到 setStage 的次数相除，就能看出写有没有落地。
        StageWriteProbe.requested(sections.length);
        StageWriteProbe.direct();
        applyStage(chunk, sections, targetStage);
    }

    /** 读回专用的落档：无条件写，绕过单调闸。语义同 {@link #stage}，供盘上档低于内存档时使用。 */
    public static void restore(LevelChunk chunk, int[] sections, int targetStage) {
        if (sections.length == 0) {
            return;
        }
        StageWriteProbe.requested(sections.length);
        StageWriteProbe.direct();
        for (int sy : sections) {
            SectionStage.restoreStage(chunk, sy, targetStage);
        }
        submit(chunk);
    }

    private static void applyStage(LevelChunk chunk, int[] sections, int targetStage) {
        for (int sy : sections) {
            SectionStage.setStage(chunk, sy, targetStage);
        }
        // 扇出：档位变了，下一相可能就绪，重新登记候选。候选被准入或拒绝后移除，不会自增长。
        submit(chunk);
    }

    /** 标脏，语义同 {@link #stage(LevelChunk, int[], int)}。 */
    public static void dirty(LevelChunk chunk, int[] sections, boolean set) {
        if (sections.length == 0) {
            return;
        }
        if (chunk.getLevel() instanceof ServerLevel level) {
            if (!SectionIO.isMainThread(level)) {
                SectionIO.runOnMainThread(() -> applyDirty(chunk, sections, set), level);
                return;
            }
        }
        applyDirty(chunk, sections, set);
    }

    private static void applyDirty(LevelChunk chunk, int[] sections, boolean set) {
        for (int sy : sections) {
            if (set) {
                SectionStage.markDirty(chunk, sy);
            } else {
                SectionStage.clearDirty(chunk, sy);
            }
        }
    }
}
