package com.inf.farlands.terrain.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.PlayerSnapshot;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 调度器：候选表、力面、准入、派发、第七行与落档体。主线程单点。
 *
 * <p>候选表按身份记，落档体的扇出、入队入口、装饰与结构相的信号都往这里 submit。每 tick 一次
 * {@link #tick()}：按距离排序、逐候选准入、过了就交出去（装饰相或生成相），第七行成立就直接补 FULL。
 *
 * <p>力面：一个面是「某个维度里、半径 radius 的方阵、段范围 [minSection, maxSection]」，由出生区
 * 预加载与 Chunky 登记。准入的竖直判据读玩家窗口与力面的并集，因为这两个面都没有玩家。
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

    /** 一个待办的 chunk。预加载段范围带 null 表示按窗口并集。 */
    private record Candidate(LevelChunk chunk, int[] preloadSections, long seq) {
    }

    private static final Map<Long, Candidate> CANDIDATES = new ConcurrentHashMap<>();

    /** 诊断行的限流：同一 chunk 同一原因只打一次。 */
    private static final Set<Long> REJECT_LOGGED = ConcurrentHashMap.newKeySet();

    /** 第七行求值行的限流：同一 chunk 只打一次。 */
    private static final Set<Long> FULL_LOGGED = ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** 该 chunk 的记账身份，0 表示客户端或 ProtoChunk。 */
    private static long identityOf(LevelChunk chunk) {
        return chunk instanceof com.inf.farlands.serialize.CounterIdentity identity ? identity.counterId() : 0L;
    }

    /** 登记一个待办 chunk，按窗口并集取段。 */
    public static void submit(LevelChunk chunk) {
        submit(chunk, null);
    }

    /** 派发重入闸：落档体的扇出会在派发内 submit，不能再递归进来。 */
    private static final ThreadLocal<Boolean> TICKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 登记一个待办 chunk，显式段范围只给预加载与任务面用。入队即派发。 */
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
        java.util.List<Candidate> sorted = new ArrayList<>(CANDIDATES.values());
        sorted.sort(java.util.Comparator.comparingLong(Scheduler::distanceOf));
        for (Candidate candidate : sorted) {
            if (budget-- <= 0) {
                break;
            }
            LevelChunk chunk = candidate.chunk();
            long key = identityOf(chunk);
            if (key == 0L) {
                continue;
            }
            CANDIDATES.remove(key);
            if (!(chunk.getLevel() instanceof ServerLevel level)) {
                continue;
            }
            int[] candRange = candidate.preloadSections();
            boolean below = SectionStage.hasBelowLighted(chunk);
            boolean wrDone = ChunkCounters.isWindowReadDone(chunk);
            // 第七行：段全点亮、窗口面读过，补 FULL。
            if (!below && wrDone) {
                if (FarlandsConfig.logScheduler && FULL_LOGGED.add(key)) {
                    InfsFarlands.LOGGER.info(
                            "FLFULL evald dim={} chunk={},{} below={} wrDone={} claimed={} holder={}",
                            level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(), below,
                            wrDone, ChunkCounters.isFullDone(chunk),
                            SectionLifecycle.latestHolder(level, chunk.getPos().x(), chunk.getPos().z()) != null);
                }
                com.inf.farlands.serialize.ChunkReadiness.completeFull(chunk);
                continue;
            }
            if (!admit(level, chunk, candRange)) {
                SchedulerMetrics.blocked();
                continue;
            }
            SchedulerMetrics.emitted();
            // 装饰在准入之后试。段全到 CARVERS 时准入的 WORK 门会挡回，所以它排在准入之前不行；
            // 这里的顺序是准入通过后先试装饰，再交生成侧。
            if (com.inf.farlands.terrain.decorationFiller.DecorationFiller.trySubmitDecoration(chunk)) {
                continue;
            }
            GenQueue.enqueueTask(chunk, candRange);
        }
    }

    /** 距离用快照，避免逐候选遍历玩家。 */
    private static long distanceOf(Candidate candidate) {
        LevelChunk chunk = candidate.chunk();
        if (chunk.getLevel() instanceof ServerLevel level) {
            return PlayerSnapshot.nearestDistance(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
        }
        return Long.MAX_VALUE;
    }

    /**
     * 准入：A 水平、B 竖直、C 依赖。
     *
     * <p>D（认领空闲）不在这里：唯一的认领点是 `GenQueue.enqueueTask` 的 CAS，它同时是去重。
     */
    private static boolean admit(ServerLevel level, LevelChunk chunk, int[] preloadSections) {
        ChunkPos pos = chunk.getPos();
        // A 水平：可玩界、票位档、距最近玩家。票位取一次游标，主线程。
        if (!WorldBounds.inChunkRange(pos.x(), pos.z())) {
            SchedulerMetrics.rejectFace();
            logReject(level, chunk, "FACE", "");
            return false;
        }
        int ticketLevel = SectionLifecycle.cursor(level).ticketLevelAt(pos.x(), pos.z());
        if (DependencyTable.allowedStage(ticketLevel) < SectionStage.TERRAIN) {
            SchedulerMetrics.rejectTicket();
            logReject(level, chunk, "TICKET",
                    "ticket=" + ticketLevel + " allowed=" + DependencyTable.allowedStage(ticketLevel));
            return false;
        }
        // A 的距离项：面内或视距加一内。面由播种侧登记，准入按它判。
        if (!GenQueue.isNearPlayer(chunk)) {
            SchedulerMetrics.rejectWindow();
            logReject(level, chunk, "WINDOW", "");
            return false;
        }
        // B 竖直：候选带的段范围，或不带范围时的窗口并集，里面还有没有生成侧的活。
        if (!hasWork(chunk, preloadSections)) {
            SchedulerMetrics.blocked();
            logReject(level, chunk, "WORK", "");
            return false;
        }
        // C 依赖：适配数据就位。未建时若 ±8 的壳齐就现场建引用相与 Beardifier，建完凭下一次提交重试。
        if (!((com.inf.farlands.terrain.ChunkBeardifier) chunk).hasBeardifier()) {
            boolean enabled = com.inf.farlands.terrain.structure.StructureDriver.structuresEnabled(level);
            boolean inRange = com.inf.farlands.terrain.structure.StructureDriver.inStructureRange(chunk);
            boolean refs = enabled && inRange
                    && com.inf.farlands.terrain.structure.StructureDriver.referencesBuildable(level, chunk);
            if (refs) {
                com.inf.farlands.terrain.structure.StructureDriver.buildReferences(level, chunk);
                ((com.inf.farlands.terrain.ChunkBeardifier) chunk).setBeardifier(
                        net.minecraft.world.level.levelgen.Beardifier.forStructuresInChunk(
                                level.structureManager(), chunk.getPos()));
                submit(chunk);
            } else {
                SchedulerMetrics.rejectReady();
                logReject(level, chunk, "READY",
                        "structures=" + enabled + " inRange=" + inRange);
            }
            return false;
        }
        return true;
    }

    /** 诊断行：准入被拒的原因与四个前件。同一 chunk 同一原因只打一次。 */
    private static void logReject(ServerLevel level, LevelChunk chunk, String reason, String extra) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        long key = (chunk.getPos().pack() << 3) | (reason.hashCode() & 7L);
        if (!REJECT_LOGGED.add(key)) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLBLOCK reject reason={} dim={} chunk={},{} {} below={} wrDone={} beard={} stages={}",
                reason, level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(), extra,
                SectionStage.hasBelowLighted(chunk), ChunkCounters.isWindowReadDone(chunk),
                ((com.inf.farlands.terrain.ChunkBeardifier) chunk).hasBeardifier(), stageHistogram(chunk));
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

    /**
     * 该 chunk 是否还有这个作业做得了的事：能收到段，或有待处理的 surface 与 carvers。
     *
     * <p>三项都排除了在读段，所以被读回标记的段不会让作业空转。此前这里按「档位低于 CARVERS」计数，
     * 那个口径比收集的过滤少一条「跳过在读段」，于是被读回标记的段被数成待办、作业却收不到段。
     *
     * <p>surface 与 carvers 两门必须在内：它们由同一个作业在 fill 之后跑，段已在 TERRAIN 或 SURFACE
     * 时收集为空，只剩这两件事可做。只按收集判会把这些段永远关在门外。
     */
    private static boolean hasWork(LevelChunk chunk, int[] preloadSections) {
        if (preloadSections != null && preloadSections.length > 0) {
            return DependencyTable.pendingBelow(chunk, SectionStage.CARVERS, preloadSections[0],
                    preloadSections[preloadSections.length - 1]) > 0;
        }
        if (DependencyTable.pendingBelow(chunk, SectionStage.CARVERS) > 0) {
            return true;
        }
        List<Face> faces = FACES.get(chunk.getLevel().dimension());
        if (faces == null) {
            return false;
        }
        for (Face face : faces) {
            if (DependencyTable.pendingBelow(chunk, SectionStage.CARVERS, face.minSection(),
                    face.maxSection()) > 0) {
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

    /** 把一段数组的档位推到目标档，语义同 {@link #stage(LevelChunk, List, int)}。 */
    public static void stage(LevelChunk chunk, int[] sections, int targetStage) {
        if (sections.length == 0) {
            return;
        }
        if (chunk.getLevel() instanceof ServerLevel level) {
            if (!SectionIO.isMainThread(level)) {
                SectionIO.runOnMainThread(() -> applyStage(chunk, sections, targetStage), level);
                return;
            }
        }
        applyStage(chunk, sections, targetStage);
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
