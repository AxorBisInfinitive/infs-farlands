package com.inf.farlands.terrain.decorationFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.debug.FreezeWatch;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.pipeline.DependencyTable;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.pipeline.OwnershipTable;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.world.WorldBounds;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 装饰阶段编排：待装饰表、邻域门、写域认领、提交到 farlands-gen、主线程收尾。
 *
 * <p>
 * 装饰是 XZ 网格驱动的 3D 写入，不能按 section 原子化：写入落在目标 chunk 与 XZ 八个邻居上，
 * 而那一圈在写入之前必须都已经过地形与雕刻。门因此取九宫格每一格都有过段、且已有段都到 CARVERS，
 * 与 vanilla ChunkPyramid 的 addRequirement(CARVERS, 1) 同源。
 *
 * <p>
 * 线程：门、认领、提交与收尾都在主线程，装饰体在 farlands-gen。取数一律走
 * {@link SectionLifecycle#latestChunk}，它只在主线程可调，所以九格句柄由主线程取好交给任务。
 *
 * <p>
 * 失败一律不抛：门没过就留表等下一轮，与 surface、carve 两族同形。
 *
 * <p>
 * 地物在这一遍里跑：池上建区域、按维度系统分派 DecorationSystem，写直进真实段；收尾回
 * 主线程装方块实体、升段、标脏、补发、触发光照。结构的两相不在这里：它必须早于 fill，落在存在流程里。
 */
public final class DecorationFiller {

    /** 门与写域的半径，取 vanilla 的 blockStateWriteRadius(1)。 */
    private static final int NEIGHBORHOOD_RADIUS = 1;

    /**
     * 放弃原因只打前若干条。门没过的项每次准入都会重试，不限流会把日志刷爆；形状与
     * SectionLifecycle 的 ENCODE_FAIL_LOGGED 相同。
     */
    private static final AtomicInteger ABORT_LOGGED = new AtomicInteger();

    /**
     * 诊断行的限流键：维度、坐标与原因三者一起判。
     *
     * <p>键里必须有维度。只按坐标记时，同一格在主世界已经打过一次，换维度之后同一格的另一条原因
     * 会被同一个键吞掉，而跨维度恰是调度的常态。
     */
    private record RejectKey(String dimension, long chunkPos, String reason) {
    }

    /** 诊断行的限流：同一维度、同一 chunk、同一原因只打一次。 */
    private static final Set<RejectKey> REJECT_LOGGED = ConcurrentHashMap.newKeySet();

    /** 本窗各出口的拒绝次数。逐条那几行只给形状，量级看 {@link #flushRejects()} 那一行。 */
    private static final AtomicLong REJECT_GATE = new AtomicLong();

    private static final AtomicLong REJECT_LOCK = new AtomicLong();

    private static final AtomicLong REJECT_CLAIMED = new AtomicLong();

    private static final AtomicLong REJECT_HANDLES = new AtomicLong();

    private static final AtomicLong REJECT_SUBMIT = new AtomicLong();

    private static final AtomicLong REJECT_NO_CARVERS = new AtomicLong();

    private static final AtomicLong FINISH_EMPTY = new AtomicLong();

    /**
     * 诊断：域锁的取与放，按四个释放点分开。
     *
     * <p>取锁数减四个释放数就是净泄漏量。分开记是因为四个释放点的线程与时机不同：三个拒绝出口与
     * {@code finishRegionNull} 就地放，{@code finish} 走主线程队列。只记总数时，一支漏放会被另一支的
     * 正常释放抵消掉。
     */
    private static final AtomicLong LOCKS_TAKEN = new AtomicLong();

    private static final AtomicLong RELEASE_ON_REJECT = new AtomicLong();

    private static final AtomicLong RELEASE_IN_FINISH = new AtomicLong();

    private static final AtomicLong RELEASE_IN_FINISH_NULL = new AtomicLong();

    /**
     * 诊断：两个收尾各自被进入的次数，判据是 {@code lockOwner != 0}，位置在取光照引擎之前。
     *
     * <p>与两个 {@code RELEASE_*} 分开记的理由：那两个埋在 {@code lightEngine != null} 的内层，引擎取不到
     * 时放锁与计数会一起静默消失，读数上表现为「进了收尾却没放」，与「没进收尾」同形。进入数在判空之前
     * 加，两支才分得开。
     */
    private static final AtomicLong ENTERED_FINISH = new AtomicLong();

    private static final AtomicLong ENTERED_FINISH_NULL = new AtomicLong();

    /**
     * 诊断：装饰提交被尝试的总次数，累计值，不清零。
     *
     * <p>累计而不按窗清零的理由是它要与调度器的分栏计数一起判读：两边都是累计，相邻两行相减就得到每秒
     * 速率，而一旦有一边清零，第一行就永远等于整段累计，速率无从算起。
     */
    private static final AtomicLong DECO_TRIES = new AtomicLong();

    /**
     * 取不到光照域锁时，下一次尝试的间隔，单位毫秒。
     *
     * <p>取锁失败来自同片区的光照任务或另一次装饰，持有期是毫秒级，所以这里只做固定间隔的重访，
     * 不做递增退避：递增需要每个 chunk 一份失败计数，那是又一张在途表。立即重投会把每次失败接成
     * 下一轮尝试，主线程与池子都烧在同一个 chunk 上。
     */
    private static final long LOCK_RETRY_MILLIS = 8L;

    /**
     * 某一格的状态变了，受影响的是该 chunk 的写域：把它的九格重新提交给调度器。
     *
     * <p>装饰的门判写域九格，所以邻域的任一格变化都可能让本项过门；扫描已删之后，这是唯一的
     * 邻域发现路径。主线程调用。
     */
    public static void cellChanged(ServerLevel level, long chunkKey) {
        int cx = ChunkPos.getX(chunkKey);
        int cz = ChunkPos.getZ(chunkKey);
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        for (int dx = -NEIGHBORHOOD_RADIUS; dx <= NEIGHBORHOOD_RADIUS; dx++) {
            for (int dz = -NEIGHBORHOOD_RADIUS; dz <= NEIGHBORHOOD_RADIUS; dz++) {
                LevelChunk chunk = cursor.at(cx + dx, cz + dz);
                if (chunk != null) {
                    Scheduler.submit("deco", chunk);
                }
            }
        }
    }

    /**
     * 调度器准入之后试提交一次装饰：护门、取九格句柄、认领写域、提交到 farlands-gen。
     *
     * <p>门与句柄都在这里一次取好，给池线程用；认领进所有权表的 DECORATED 相，与光照侧互斥。
     * 主线程调用。
     *
     * @return 是否真的提交了
     */
    public static boolean trySubmitDecoration(LevelChunk center) {
        DECO_TRIES.incrementAndGet();
        // 等待那一格的诊断：全局计数分不出这一格有没有被碰过，所以另记三个。
        boolean watched = FreezeWatch.isWatched(center);
        if (watched) {
            FreezeWatch.watchedTry();
        }
        // 待装饰的判据与门都在依赖表那一侧求值，是唯一表达式。
        boolean[] anyCarvers = { false };
        SectionStage.forEachStage(center, (sy, stage) -> {
            if (stage == SectionStage.CARVERS) {
                anyCarvers[0] = true;
            }
        });
        if (!anyCarvers[0]) {
            REJECT_NO_CARVERS.incrementAndGet();
            logReject(center, "no-carvers");
            return false;
        }
        if (!DependencyTable.neighborGate(center)) {
            REJECT_GATE.incrementAndGet();
            if (watched) {
                FreezeWatch.watchedGateFail();
            }
            logReject(center, "gate", () -> DependencyTable.neighborGateAll(center));
            return false;
        }
        ServerLevel level = (ServerLevel) center.getLevel();
        // 先取光照域锁，再收句柄。反过来的代价实测过：句柄在结构维度要查 729 格的读域，取锁却排在
        // 它之后，取不到时这 729 次查询已经付掉，77 次失败就是约 5.6 万次无效查询加 77 个池任务。
        // 代价是持有期含池排队，与那笔浪费相比量级更小。
        FarLandsLightEngine lightEngine = lightEngineOf(level);
        long lockOwner = 0L;
        if (lightEngine != null) {
            lockOwner = lightEngine.tryLockDomain(center.getPos().x(), center.getPos().z());
            if (lockOwner == 0L) {
                REJECT_LOCK.incrementAndGet();
                logReject(center, "lock",
                        () -> OwnershipTable.lightDomainOwners(center.getPos().x(), center.getPos().z()));
                return false;
            }
            LOCKS_TAKEN.incrementAndGet();
            if (watched) {
                FreezeWatch.watchedLockOk();
            }
        }
        int readRadius = center.getAllReferences().isEmpty() ? DecorationRegion.READ_RADIUS
                : StructureDriver.STRUCTURE_READ_RADIUS;
        Long2ObjectMap<LevelChunk> handles = collectHandles(level, center, readRadius);
        if (handles == null) {
            unlockDomain(lightEngine, center, lockOwner);
            REJECT_HANDLES.incrementAndGet();
            logReject(center, "no-handles");
            return false;
        }
        long decoOwner = OwnershipTable.tryClaimChunk(level, OwnershipTable.DECORATED, center.getPos());
        if (decoOwner == 0L) {
            unlockDomain(lightEngine, center, lockOwner);
            REJECT_CLAIMED.incrementAndGet();
            logReject(center, "claimed");
            return false;
        }
        int descentFloorY = descentFloorY(handles, center);
        Key key = keyOf(center);
        DecorationTask task = new DecorationTask(level, center, key, handles, readRadius, descentFloorY,
                decoOwner, lockOwner);
        if (!GenQueue.submitDecoration(task)) {
            OwnershipTable.releaseChunk(level, OwnershipTable.DECORATED, center.getPos(), decoOwner);
            unlockDomain(lightEngine, center, lockOwner);
            REJECT_SUBMIT.incrementAndGet();
            logReject(center, "rejected");
            return false;
        }
        return true;
    }

    /** 释放本类的光照域锁。{@code lockOwner} 为 0 表示没取到，什么都不做。 */
    private static void unlockDomain(FarLandsLightEngine lightEngine, LevelChunk center, long lockOwner) {
        if (lockOwner != 0L && lightEngine != null) {
            lightEngine.unlockDomain(center.getPos().x(), center.getPos().z(), lockOwner);
            RELEASE_ON_REJECT.incrementAndGet();
        }
    }

    /**
     * 诊断行：装饰提交被拒的原因。同一 chunk 同一原因只打一次。
     *
     * <p>四个出口原本都是静默的，而「提交了但取不到光照域锁」那条也静默，见
     * {@link #finishRegionNull}。没有这一行就分不开「没提交」与「提交了但拿不到锁」，
     * 也就数不出泄漏发生在认领还是域锁上。
     */
    private static void logReject(LevelChunk center, String reason) {
        logReject(center, reason, null);
    }

    /**
     * 同上，附带一条原因细节。
     *
     * <p>{@code detail} 迟一层求值：限流键判过之后才构造。九格门的全量明细要扫九格档位表，而拒绝
     * 每 50 毫秒就可能来一轮，把它放在键判之前等于每秒扫几百次。
     */
    private static void logReject(LevelChunk center, String reason, Supplier<String> detail) {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        ServerLevel level = (ServerLevel) center.getLevel();
        RejectKey key = new RejectKey(String.valueOf(level.dimension().identifier()),
                center.getPos().pack(), reason);
        if (!REJECT_LOGGED.add(key)) {
            return;
        }
        String text = detail == null ? null : detail.get();
        if (text == null) {
            InfsFarlands.LOGGER.info("FLDECO reject reason={} dim={} chunk={},{}",
                    reason, level.dimension().identifier(), center.getPos().x(), center.getPos().z());
        } else {
            InfsFarlands.LOGGER.info("FLDECO reject reason={} detail={} dim={} chunk={},{}",
                    reason, text, level.dimension().identifier(), center.getPos().x(), center.getPos().z());
        }
    }

    /**
     * 每 200 tick 一行：本窗各出口的次数。
     *
     * <p>逐条那几行只留第一次出现的形状，重复被限流压掉，所以总量只能从这一行看。{@code rows} 是
     * 本窗已打过的去重键总数，也是逐条探针自身的有效性证据。
     */
    public static void flushRejects() {
        if (!FarlandsConfig.logScheduler) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLDECO sum gate={} lock={} claimed={} handles={} rejected={} noCarvers={} finishEmpty={} rows={}",
                REJECT_GATE.getAndSet(0L), REJECT_LOCK.getAndSet(0L), REJECT_CLAIMED.getAndSet(0L),
                REJECT_HANDLES.getAndSet(0L), REJECT_SUBMIT.getAndSet(0L), REJECT_NO_CARVERS.getAndSet(0L),
                FINISH_EMPTY.getAndSet(0L), REJECT_LOGGED.size());
    }

    private record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    private DecorationFiller() {
    }

    private static Key keyOf(LevelChunk chunk) {
        return new Key(((ServerLevel) chunk.getLevel()).dimension(), chunk.getPos().pack());
    }

    /**
     * 登记待装饰。雕刻完成后由 GenTask 调，带上本次雕刻的段；扫描路径调它兜底，传 null。
     *
     * <p>
     * 装饰的发现现在由调度器负责：段升到 CARVERS 时落档体会提交该 chunk，准入时由
     * {@link #trySubmitDecoration} 扫段表问「有没有 CARVERS 段」。本方法只剩界外 chunk 的 VOID 收口。
     *
     * <p>
     * 段界之外的 chunk 按 VOID 装饰处理，见 {@link #completeAsVoid}：它们的 blockpos 已经溢出，
     * 装饰只会在写域外空转，而那条越域日志没有上限。
     */
    public static void register(LevelChunk chunk, int[] carvedSections) {
        if (!WorldBounds.inChunkRange(chunk.getPos().x(), chunk.getPos().z())) {
            completeAsVoid(chunk);
        }
    }

    /**
     * 界外 chunk 的装饰按 VOID 处理：不放任何地物与结构，但装饰这一步必须记成已完成。
     *
     * <p>就绪判据要求已物化段都不低于 LIGHTED，而停在 CARVERS 的段只由装饰推进。把界外 chunk 的段
     * 留在 CARVERS，它的 FULL 就永远补不上，任何跨界的阻塞取 chunk 都会把主线程挂死。
     *
     * <p>形状与 VOID 装饰系统一致：那边什么都不放，段照样升 DECORATED、照样点亮。这里一个绝对坐标
     * 都不推，所以不会有越域写入。
     */
    private static void completeAsVoid(LevelChunk chunk) {
        List<Integer> carvers = new ArrayList<>();
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage == SectionStage.CARVERS) {
                carvers.add(sy);
            }
        });
        if (carvers.isEmpty()) {
            return;
        }
        int[] completed = new int[carvers.size()];
        for (int i = 0; i < completed.length; i++) {
            completed[i] = carvers.get(i);
        }
        Scheduler.stage(chunk, completed, SectionStage.DECORATED);
        Scheduler.dirty(chunk, completed, true);
        StageMetrics.stageSections(StageMetrics.STAGE_DECORATED, completed.length);
        // 这一格的段刚从 CARVERS 升到 DECORATED：对它八个邻居的门来说，这一格的状态变了。
        cellChanged((ServerLevel) chunk.getLevel(), chunk.getPos().pack());
        // DECORATED 到 LIGHTED 只能由光照回调推进，所以这里必须触发一次光照。
        GenQueue.notifyGenerated(chunk);
    }

    /**
     * 每 tick 一次的主线程驱动：逐项判门、取句柄、认领写域与光照域、提交到 farlands-gen。
     *
     * <p>
     * 提交成功的项留表直到收尾删掉；这期间认领把同一个中心挡在门外，所以不会重复提交。
     *
     * <p>
    /**
     * 该 level 的光照引擎。非本 port 引擎返回 null，此时装饰侧不取光照域锁。
     */
    private static FarLandsLightEngine lightEngineOf(ServerLevel level) {
        return level.getChunkSource().getLightEngine() instanceof FarLandsLightEngine engine ? engine : null;
    }

    /**
     * 地物下行扫描的地板：读域里最低的已物化段。没有段时是 {@link Integer#MIN_VALUE}，即不设下界。
     *
     * <p>取最小值而不是中心那一个，因为下行循环拿不到列坐标，对每一列都成立的地板必须不高于任何一列的
     * 最低段。走段容器而不是窗口：服务端 chunk 的窗口是构造默认的死状态，不跟生成走。主线程取好交下去，
     * 池上不再读那些字段。
     */
    private static int descentFloorY(Long2ObjectMap<LevelChunk> handles, LevelChunk center) {
        int floor = DecorationRegion.lowestSectionFloor(center);
        for (LevelChunk chunk : handles.values()) {
            int candidate = DecorationRegion.lowestSectionFloor(chunk);
            if (candidate < floor) {
                floor = candidate;
            }
        }
        return floor;
    }

    /**
     * 写域九格的句柄，另加读域外环的句柄。门已由 {@link DependencyTable#neighborGate} 判过，这里只取数。
     *
     * <p>句柄在这里取好交给任务：chunk 的窗口容器与服务端的 chunk 表都不是线程安全的，池线程不得
     * 再查，所以取数是主线程的事。写域即 ±1 必须齐；读域外环到 {@link DecorationRegion#READ_RADIUS}
     * 有就带上、没有就跳过，地物偶尔会读到写域外一格，缺句柄时那一次读会放弃并记日志。
     */
    private static Long2ObjectMap<LevelChunk> collectHandles(ServerLevel level, LevelChunk center, int readRadius) {
        Long2ObjectMap<LevelChunk> handles = new Long2ObjectOpenHashMap<>(
                (readRadius * 2 + 1) * (readRadius * 2 + 1));
        ChunkPos pos = center.getPos();
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        for (int dx = -NEIGHBORHOOD_RADIUS; dx <= NEIGHBORHOOD_RADIUS; dx++) {
            for (int dz = -NEIGHBORHOOD_RADIUS; dz <= NEIGHBORHOOD_RADIUS; dz++) {
                LevelChunk neighbor = cursor.at(pos.x() + dx, pos.z() + dz);
                if (neighbor == null) {
                    // 门已判过在场，这一支是防御：取不到壳就没有句柄可用。
                    return null;
                }
                handles.put(neighbor.getPos().pack(), neighbor);
            }
        }
        for (int dx = -readRadius; dx <= readRadius; dx++) {
            for (int dz = -readRadius; dz <= readRadius; dz++) {
                if (Math.abs(dx) <= NEIGHBORHOOD_RADIUS && Math.abs(dz) <= NEIGHBORHOOD_RADIUS) {
                    continue; // 写域那九格已经收过
                }
                LevelChunk neighbor = cursor.at(pos.x() + dx, pos.z() + dz);
                if (neighbor != null) {
                    handles.put(neighbor.getPos().pack(), neighbor);
                }
            }
        }
        return handles;
    }

    /**
     * 一个装饰任务。跑在 farlands-gen 上：建区域、调该维度的装饰系统，然后无论成败都回主线程
     * 收尾。
     */
    private static final class DecorationTask implements Runnable {

        private final ServerLevel level;
        private final LevelChunk center;
        private final Key key;
        private final Long2ObjectMap<LevelChunk> handles;
        /** 本任务的读半径：由主线程按中心有无引用定好交下来，池上不再读那张表。 */
        private final int readRadius;
        /** 本任务的下行地板：由主线程按读域最低已物化段算好交下来，池上不再读那些字段。 */
        private final int descentFloorY;
        /** 本任务持有的 DECORATED 认领编号，收尾时带回去释放。 */
        private final long decoOwner;

        /**
         * 本任务持有的光照域锁编号，0 表示没有引擎可取锁。锁在主线程取、在收尾放，任务体不再自己取：
         * 取锁失败要在收句柄之前就返回，见 {@code trySubmitDecoration}。
         */
        private final long lockOwner;

        DecorationTask(ServerLevel level, LevelChunk center, Key key, Long2ObjectMap<LevelChunk> handles,
                int readRadius, int descentFloorY, long decoOwner, long lockOwner) {
            this.level = level;
            this.center = center;
            this.key = key;
            this.handles = handles;
            this.readRadius = readRadius;
            this.descentFloorY = descentFloorY;
            this.decoOwner = decoOwner;
            this.lockOwner = lockOwner;
        }

        @Override
        public void run() {
            DecorationRegion region = null;
            long lockOwner = this.lockOwner;
            try {
                DecorationContext.enter();
                try {
                    region = new DecorationRegion(this.level, this.center, this.handles, this.readRadius,
                            this.descentFloorY);
                    // 结构起点的 Y 锚会经 getFirstOccupiedHeight 构造 NoiseChunk，而 NoiseChunk 的
                    // 构造点要求 TerrainSystemContext 已设，否则抛。夹法照 fill 的形状。
                    TerrainSystemContext.set(((LevelSystems) this.level).terrainSystem());
                    long decStart = System.nanoTime();
                    try {
                        ((LevelSystems) this.level).decorationSystem().applyDecoration(this.level, this.center,
                                region);
                    } finally {
                        TerrainSystemContext.clear();
                        StageMetrics.stageWork(StageMetrics.STAGE_DECORATED, System.nanoTime() - decStart);
                    }
                } finally {
                    DecorationContext.exit();
                }
            } catch (DecorationAbort abort) {
                // 条件不成立：放弃本次，丢弃已建的方块实体，留表下一轮。已直写进段的方块不回滚，
                // 下一轮的落点以当时内容为准，与放弃并重试的语义一致。
                if (ABORT_LOGGED.getAndIncrement() < 20) {
                    InfsFarlands.LOGGER.info("farlands: decoration aborted chunk={},{} reason={}",
                            this.center.getPos().x(), this.center.getPos().z(), abort.getMessage());
                }
                region = null;
            } catch (RuntimeException e) {
                InfsFarlands.LOGGER.error("farlands: decoration failed chunk={},{}",
                        this.center.getPos().x(), this.center.getPos().z(), e);
                region = null;
            } catch (Throwable t) {
                // 池任务走 POOL.submit，没人读那个 Future，任何 Error 都会被静默吞掉。这里显式打出来，
                // 并标明它死在取数之前还是之后：region 未赋值时收尾不会升段，症状正是装饰永远不完成。
                InfsFarlands.LOGGER.error("farlands: decoration threw chunk={},{} regionAssigned={}",
                        this.center.getPos().x(), this.center.getPos().z(), region != null, t);
                region = null;
            } finally {
                // 分流：region 为 null 时本次一个方块都没写，没有任何主线程专属的收尾工作，收尾就在池
                // 线程内做完，让认领早释放。认领挡的是照光侧，而它在主线程队列里等一笔空的收尾时，照光
                // 池就被按住了。
                //
                // 带锁收尾必须回主线程：装方块实体、升段、标脏、补发、触发光照五件都要求主线程，而它们
                // 只在真的装饰过、即 region 非 null 时才存在。
                DecorationRegion computed = region;
                long computedOwner = lockOwner;
                // 侧信道是本线程的，两支收尾都在本方法返回前落下，清在这里最省事也最可靠。
                DecorationRegion.clearHandedChunkKey();
                if (computed == null) {
                    finishRegionNull(this.level, this.center, this.key, computedOwner, this.decoOwner);
                } else {
                    SectionIO.runOnMainThread(
                            () -> finish(this.level, this.center, this.key, computed, computedOwner,
                                    this.decoOwner),
                            this.level);
                }
            }
        }
    }

    /**
     * 没写成任何东西的那一支的收尾。在 farlands-gen 上执行，不走主线程。
     *
     * <p>
     * 为什么可以不走主线程：这一支一个方块都没写，所以没有方块实体要装、没有段要升、没有下发要
     * 补、没有光照要触，而主线程专属的工作只有那四件。留下的两件，撤认领与清在途计数，都有并发安全
     * 的载体。
     *
     * <p>
     * 为什么必须不走主线程：认领挡的是照光侧的取域，而收尾走主线程时认领要在主线程队列里排到这一
     * 笔被执行才撤，于是持有期被拉长到「池队列等待加收尾」的量级；而这一支一个方块都没写，占提交数的
     * 九成上下，等于用一笔空收尾按住照光池。
     */
    private static void finishRegionNull(ServerLevel level, LevelChunk center, Key key, long lockOwner,
            long decoOwner) {
        // 这一支是「提交过但没写成方块」的唯一出口。锁在提交时已经取到，所以这里的 lockOwner 非零；
        // 只有引擎不在场的客户端构造才是 0，那种情况留作 lock 记一笔。
        logReject(center, lockOwner == 0L ? "lock" : "abort");
        OwnershipTable.releaseChunk(level, OwnershipTable.DECORATED, center.getPos(), decoOwner);
        if (lockOwner != 0L) {
            ENTERED_FINISH_NULL.incrementAndGet();
            FarLandsLightEngine lightEngine = lightEngineOf(level);
            if (lightEngine != null) {
                lightEngine.unlockDomain(center.getPos().x(), center.getPos().z(), lockOwner);
                RELEASE_IN_FINISH_NULL.incrementAndGet();
            }
        }
        GenQueue.finishDecoration();
        // 认领已撤：该 chunk 的「装饰写域被占」这一条落了，可能就此就绪，交准入。取锁失败那一支要
        // 隔一段再试：立即重投会把每次失败接成下一轮 collectHandles 与一次池任务，热环的来源。
        SectionIO.runOnMainThread(() -> Scheduler.submitLater(center, LOCK_RETRY_MILLIS), level);
    }

    /**
     * 收尾。主线程执行：装方块实体、把该 chunk 已过雕刻的段升 DECORATED、标脏、补发、触发光照，
     * 并撤两把锁与在途计数。
     *
     * <p>
     * 升段只认 CARVERS，即该 chunk 自己的那一段：邻居被写到的段不跟着升，否则被写到会被当成
     * 它自己的装饰完成，而那正是本档要消灭的那个混淆。
     *
     * <p>
     * {@code region} 为 null 表示本次放弃，取数不成立或抛异常，此时只撤锁、不升段，下一轮重新提交。
     *
     * <p>
     * 不变式：装饰完成的判据是段升了档，不是任务跑完了。所以放弃、收尾时取不到 chunk、没有段停在
     * CARVERS 这三种情形都不升段，段留在原档等下一次提交的准入。
     */
    private static void finish(ServerLevel level, LevelChunk center, Key key, DecorationRegion region,
            long lockOwner, long decoOwner) {
        // region 为 null 的那一支已在 finishRegionNull 里于池线程收尾，本方法只收「真的装饰过」的。
        try {
            LevelChunk current = SectionLifecycle.latestChunk(level, center.getPos().x(), center.getPos().z());
            if (current == null) {
                return;
            }
            // 被本次装饰换成非 BE 方块的那些位置：那里的活实例要在这里摘掉，否则留成孤儿。孤儿过不了
            // 服务端的刻判据，客户端还会打 invalid for ticking，而直写段绕开了 LevelChunk.setBlockState
            // 的摘除，所以只能由本收尾补。判据落在当前方块上：后来者若又合法铺回 BE 方块并装了实例，
            // 这里就放过它；不存在的实例 removeBlockEntity 自己幂等。
            for (BlockPos pos : region.pendingBlockEntityRemovals()) {
                LevelChunk owner = SectionLifecycle.latestChunk(level, SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()));
                if (owner == null) {
                    continue;
                }
                if (!owner.getBlockState(pos).hasBlockEntity()) {
                    owner.removeBlockEntity(pos);
                }
            }
            // 方块实体按各自所属的 chunk 安装：写域是九格，实体可能落在邻居上。
            for (Map.Entry<BlockPos, BlockEntity> e : region.pendingBlockEntities().entrySet()) {
                BlockPos pos = e.getKey();
                LevelChunk owner = SectionLifecycle.latestChunk(level, SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()));
                if (owner == null) {
                    continue;
                }
                BlockEntity blockEntity = e.getValue();
                blockEntity.setLevel(level);
                owner.setBlockEntity(blockEntity);
                // 标脏在这里补：装饰期的方块实体改动发生在池线程上，而那条路上唯一的标脏入口
                // BlockEntity.setChanged 到 Level.blockEntityChanged 会在非服务端线程上被短路，
                // 因为它为了一个 unsaved 布尔值要做一次阻塞取数。不在这里补，方块实体的 NBT
                // 只活在内存里，重进即回退。setBlockEntity 自己不标脏。
                owner.markUnsaved();
            }
            // 下发标记：写域是九格，邻居的方块也被写过，它们各自的下发要跟着这次装饰走。与升段解耦：
            // 写过就得补发，不管本次有没有可升的段。
            for (LongIterator it = region.writtenChunks().iterator(); it.hasNext();) {
                long writtenKey = it.nextLong();
                LevelChunk owner = SectionLifecycle.latestChunk(level, ChunkPos.getX(writtenKey),
                        ChunkPos.getZ(writtenKey));
                if (owner != null) {
                    ChunkDataSender.markChunkChanged(owner);
                }
            }
            List<Integer> carvers = new ArrayList<>();
            SectionStage.forEachStage(current, (sy, stage) -> {
                if (stage == SectionStage.CARVERS) {
                    carvers.add(sy);
                }
            });
            if (carvers.isEmpty()) {
                // 任务跑完了，却没有一段停在 CARVERS：要么段已被别处推进，要么这一遍没写成方块。
                // 一次性一行，用来分开「提交成功却不升段」与「根本没提交」这两支。
                FINISH_EMPTY.incrementAndGet();
                logReject(current, "finish-empty");
                return;
            }
            int[] decorated = new int[carvers.size()];
            for (int i = 0; i < decorated.length; i++) {
                decorated[i] = carvers.get(i);
            }
            Scheduler.stage(current, decorated, SectionStage.DECORATED);
            Scheduler.dirty(current, decorated, true);
            StageMetrics.stageSections(StageMetrics.STAGE_DECORATED, decorated.length);
            // 这一格的段刚从 CARVERS 升到 DECORATED：对它八个邻居的门来说，这一格的状态变了。
            cellChanged(level, current.getPos().pack());
            // DECORATED 到 LIGHTED 只能由光照回调推进，所以这里必须触发一次光照。
            GenQueue.notifyGenerated(current);
        } finally {
            OwnershipTable.releaseChunk(level, OwnershipTable.DECORATED, center.getPos(), decoOwner);
            // 只有真的取到过锁才释放：取域返回的持有者编号非 0 才算取到。取锁失败那一支是一个方块
            // 都没写的放弃，把 0 传下去不会碰到任何别人持有的格。
            if (lockOwner != 0L) {
                ENTERED_FINISH.incrementAndGet();
                FarLandsLightEngine lightEngine = lightEngineOf(level);
                if (lightEngine != null) {
                    lightEngine.unlockDomain(center.getPos().x(), center.getPos().z(), lockOwner);
                    RELEASE_IN_FINISH.incrementAndGet();
                }
            }
            GenQueue.finishDecoration();
            // 认领已撤：该 chunk 的「装饰写域被占」这一条落了，可能就此就绪，交准入。
            Scheduler.submit("deco", center);
        }
    }

    /**
     * 诊断：域锁的取与四个释放点压成一行，取走本窗的累计计数。
     *
     * <p>落点只能是冻结期仍在执行的路径，所以由泵的心跳调用，不由 200 tick 块调用。
     */
    public static String lockSummary() {
        return "decoTry=" + DECO_TRIES.get()
                + " lockTaken=" + LOCKS_TAKEN.getAndSet(0L)
                + " entFinish=" + ENTERED_FINISH.getAndSet(0L)
                + " entNull=" + ENTERED_FINISH_NULL.getAndSet(0L)
                + " relReject=" + RELEASE_ON_REJECT.getAndSet(0L)
                + " relFinish=" + RELEASE_IN_FINISH.getAndSet(0L)
                + " relNull=" + RELEASE_IN_FINISH_NULL.getAndSet(0L);
    }

    /** 停服时清空本类的进程级状态。认领表由 `OwnershipTable.clearWorldState` 清。 */
    public static void clearWorldState() {
        ABORT_LOGGED.set(0);
        REJECT_LOGGED.clear();
        REJECT_GATE.set(0L);
        REJECT_LOCK.set(0L);
        REJECT_CLAIMED.set(0L);
        REJECT_HANDLES.set(0L);
        REJECT_SUBMIT.set(0L);
        REJECT_NO_CARVERS.set(0L);
        FINISH_EMPTY.set(0L);
        LOCKS_TAKEN.set(0L);
        RELEASE_ON_REJECT.set(0L);
        RELEASE_IN_FINISH.set(0L);
        RELEASE_IN_FINISH_NULL.set(0L);
        ENTERED_FINISH.set(0L);
        ENTERED_FINISH_NULL.set(0L);
        DECO_TRIES.set(0L);
    }
}
