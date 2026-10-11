package com.inf.farlands.terrain.pipeline;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.surfaceFiller.SurfaceFiller;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * chunk 级生成任务，状态转换批。一个任务对应一个 chunk，execute 时收集玩家窗口并集内该
 * chunk 未生成的 section，按连续段分组，每段一个 NoiseChunk 填充，固定成本从每 section 一次
 * 降到每段一次。
 *
 * 瞬态：触发即建，执行即毁。持 LevelChunk 强引用以防 GC。fill 在途由 GEN_WORK_TICKET 保加载，
 * chunk 不卸载，光照播种对象始终正确。
 *
 * 并发：同 chunk 至多一个任务在途，判据是 {@link OwnershipTable} 上该 chunk 的认领，因此 fill 无
 * 并发写高度图。不同 chunk 并行。
 */
public final class GenTask {

    private final LevelChunk chunk;
    /** 预加载模式：指定 section 集合。null 表示窗口模式，用玩家窗口并集。 */
    private final int[] preloadSections;
    /**
     * 排序键：到最近玩家的三维欧几里得距离的平方，单位是方块。用平方避免开方，用 long 避免溢出；
     * 三项之和在上界处饱和。无玩家时取 0，退化为 FIFO。
     */
    private long priority;

    /** 入队序号，距离并列时按它定序，使同一份输入的推进次序可复现。 */
    private final long sequence = Scheduler.nextSequence();

    /** 本次作业的认领编号，由调度器在准入时落下，收尾时按它释放。0 表示未认领。 */
    private long owner;

    /**
     * 本次执行是否停在门上，即缺 Beardifier。它决定收尾走哪一支：被挡下的那一支不续任务，
     * 由兜底重扫与 farlandsStructureReady 的回投再触发。
     */
    private boolean blocked;

    public GenTask(LevelChunk chunk) {
        this(chunk, null);
    }

    public GenTask(LevelChunk chunk, int[] preloadSections) {
        this.chunk = chunk;
        this.preloadSections = preloadSections;
        this.priority = computePriority(chunk, preloadSections);
    }

    LevelChunk chunk() {
        return this.chunk;
    }

    long chunkKey() {
        return this.chunk.getPos().pack();
    }

    long priority() {
        return this.priority;
    }

    /** 玩家位置变化时由调度器调，按当前位置重算排序键。 */
    void refreshPriority() {
        this.priority = computePriority(this.chunk, this.preloadSections);
    }

    long sequence() {
        return this.sequence;
    }

    int[] preloadSections() {
        return this.preloadSections;
    }

    boolean isPreload() {
        return this.preloadSections != null;
    }

    /**
     * 本作业推进到的档。它是段带类作业，正文把 TERRAIN 之后的 surface 与 carvers 一并做完，所以按
     * 它的第一档 TERRAIN 报给准入，票位门据此判。
     */
    int targetStage() {
        return SectionStage.TERRAIN;
    }

    long owner() {
        return this.owner;
    }

    void setOwner(long owner) {
        this.owner = owner;
    }

    /** 距最近玩家的三维欧几里得距离的平方。无玩家时返回 0，退化为 FIFO。 */
    private static long computePriority(LevelChunk chunk, int[] preloadSections) {
        if (chunk.getLevel() instanceof ServerLevel sl) {
            ChunkPos cp = chunk.getPos();
            long best = Long.MAX_VALUE;
            for (ServerPlayer p : sl.players()) {
                ChunkPos pp = p.chunkPosition();
                long dx = ((long) cp.x() - pp.x()) << 4;
                long dz = ((long) cp.z() - pp.z()) << 4;
                // 纵向：预加载作业按段带中心算，窗口作业按玩家自身段算，后者纵向项为 0
                long dy = 0L;
                if (preloadSections != null && preloadSections.length > 0) {
                    int centerSy = preloadSections[preloadSections.length / 2];
                    dy = ((long) centerSy - (p.getBlockY() >> 4)) << 4;
                }
                long distance = saturatingSquare(dx) + saturatingSquare(dy) + saturatingSquare(dz);
                if (distance < best) {
                    best = distance;
                }
            }
            return best == Long.MAX_VALUE ? 0L : best;
        }
        return 0L;
    }

    /** 平方并饱和，防三项之和越过 long。 */
    private static long saturatingSquare(long value) {
        if (value > 3037000499L || value < -3037000499L) {
            return Long.MAX_VALUE / 4;
        }
        return value * value;
    }

    public void execute() {
        try {
            Level level = chunk.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            // 门查不变量本身：没有 Beardifier 就不许 fill。fill 会读它，未设即抛，读点在
            // AbstractTerrainFiller.fill。只看 isAwaiting 不够：登记的落点晚于这个 chunk 对
            // latestChunk 可见的那一刻，中间那道窗口里进池的任务会撞上未设的 Beardifier。这道门也
            // 不能挪进就绪判据：FULL 若等结构相，setInitialSpawn 那条阻塞读会与造出 ±8 壳的那条链
            // 互为条件死等。
            if (!((ChunkBeardifier) chunk).hasBeardifier()) {
                this.blocked = true;
                // 拉依赖必须在主线程，票操作非线程安全，latestChunk 也只在主线程，而本任务在池上。
                // 排一笔回主线程，早退保持即时。不是挂起项时 pullDependencies 自己会早退。
                SectionIO.runOnMainThread(() -> StructureDriver.pullDependencies(serverLevel, chunk), serverLevel);
                // 这里早退而不续任务：门能不能过只由 ±8 的壳决定，重试改变不了它，而重试消耗的恰是
                // 解开自己要用的池与主线程。被挡的 chunk 交给扫描与 farlandsStructureReady 的回投。
                return;
            }
            List<int[]> segments = collectSegments();
            for (int[] seg : segments) {
                // 先补 biome 再推 TERRAIN。本方法是全流程唯一把 stage 推到 TERRAIN 的地方，放这里就与
                // 入队顺序无关；若让生成先跑，stage 越过 BIOMES，fillSectionBiomes 的 stage < BIOMES 门
                // 会永假，该段 biome 永久停在 PalettedContainerFactory 的默认群系 plains，地表规则随之
                // 按 plains 跑，密度依赖 biome 的系统连形状一起错。
                //
                // 不需要用 BIOME_FILLING 包住这一段：本任务全程持有认领，发送侧一律看到 isChunkBusy 为真
                // 并返回空表，生成期间不会读这一段。那个标志是给短路完成后跑在 backgroundExecutor 上的
                // biome 阶段用的，见 WindowSendState.sendableSections。
                for (int sy = seg[0]; sy <= seg[1]; sy++) {
                    BiomeFiller.fillSectionBiomes(serverLevel, chunk, sy);
                }
                long fillStart = System.nanoTime();
                try {
                    com.inf.farlands.terrain.LevelSystems systems = (com.inf.farlands.terrain.LevelSystems) serverLevel;
                    systems.terrainFiller().fill(serverLevel, chunk, seg[0], seg[1]);
                } catch (Exception e) {
                    InfsFarlands.LOGGER.error("GENTASK fill ex chunk={},{} {}",
                            chunk.getPos().x(), chunk.getPos().z(), e.toString());
                    throw e;
                }
                StageMetrics.stageWork(StageMetrics.STAGE_TERRAIN, System.nanoTime() - fillStart);
                for (int sy = seg[0]; sy <= seg[1]; sy++) {
                    SectionStage.setStage(chunk, sy, SectionStage.TERRAIN);
                    // fsa 脏标记：fill 在 genPool 线程写 section 内容，CHM 安全
                    ((WindowedChunk) chunk).markSectionDirty(sy);
                }
                StageMetrics.stageSections(StageMetrics.STAGE_TERRAIN, seg[1] - seg[0] + 1);
            }
            // SURFACE 独立于 segments，fill 后紧跟，因为依赖 fill 产出的高度图，也覆盖读回
            // stage 为 TERRAIN 与 surface 失败残留。失败不抛，section 停留 TERRAIN，由兜底重扫与调用
            // 方的重提再触发。不触发光照，promoteAllGenToLighted 会把 TERRAIN 升 LIGHTED，抹掉待处理标志。
            try {
                SurfaceFiller.applySurfaceIfNeeded(serverLevel, chunk);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("GENTASK surface ex chunk={},{} {}",
                        chunk.getPos().x(), chunk.getPos().z(), e.toString());
            }
            // CARVERS 独立于 segments 与 surface，surface 后紧跟，只替换 fill 产物方块。
            // 失败处理同 surface。
            int[] carved = new int[0];
            try {
                carved = CarverFiller.applyCarversIfNeeded(serverLevel, chunk);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("GENTASK carvers ex chunk={},{} {}",
                        chunk.getPos().x(), chunk.getPos().z(), e.toString());
            }
            // 雕刻完成即登记装饰：这一步的段是 CARVERS，装饰的门要九宫格，判据与重试都在
            // DecorationFiller。门没过只留表，不影响本任务收尾；扫描路径另有兜底登记。
            if (carved.length > 0) {
                DecorationFiller.register(chunk, carved);
                // 这一格刚过雕刻：对它八个邻居的门来说，这一格从低于 CARVERS 变成就绪。
                DecorationFiller.cellChanged(chunk.getPos().pack());
                // 池线程只投事件，调度器在主线程据此重判这个 chunk 的下游是否就绪。
                Scheduler.postCellChanged(chunk);
            }
            // 光照触发条件：carvers 产出了东西，或已有光照能升、但还没升的段。CARVERS 不在判据内：
            // 那一段的下一步是装饰而不是光照，promote 也不升它，算进来只会每 tick 白跑一次光照。
            int[] awaitingLight = awaitingLightSections();
            if (carved.length > 0 || awaitingLight.length > 0) {
                // 恢复的段不脏，applyDecoded 只恢复 stage。不标脏则 persistChunkDirty 写不到它，
                // stage 会一直停在盘上的旧值，每次重进都要重算一遍光照。
                for (int sy : awaitingLight) {
                    ((WindowedChunk) chunk).markSectionDirty(sy);
                }
                GenQueue.notifyGenerated(chunk);
                // fill、surface、carvers 直接写 section，没有 vanilla 广播；这里只标记该 chunk
                // 内容已变，由 ChunkDataSender 每 tick 按玩家当前窗口物化后再发段包。放在
                // notifyGenerated 之后，flush 时 LIGHT_IN_FLIGHT 必为真，于是留队列等光照完成，
                // 方块与光照同到。
                ChunkDataSender.markChunkChanged(chunk);
            }
        } finally {
            // fill 异常也清理：释放认领与票，并按 blocked 或续任务走收尾。异常路径若不清理会让
            // 认领残留，chunk 再也拿不到新作业。
            Scheduler.finishJob(this, this.blocked);
        }
    }

    /**
     * 光照会升、但还没升的 section，即 SectionStage.isAwaitingLight 认的三档：TERRAIN、SURFACE、
     * DECORATED。它是本任务补触发光照的判据；判据的出处只有 SectionStage 那一处，避免与
     * promoteAllGenToLighted 的匹配面失同步。
     *
     * <p>CARVERS 不在判据内：它的出路是装饰，光照不会升它。UNPROCESSED 与 BIOMES 也不在：
     * 窗口内的由生成收口，窗口外的永不处理。
     *
     * <p>按 stage 载体取，不按 allSections 取：非噪声生成器的维度里 fill 会早退、段没被建出来，
     * 而 GenTask 已经推进了 stage，按 allSections 会漏掉它们。
     */
    private int[] awaitingLightSections() {
        List<Integer> out = new ArrayList<>();
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (SectionStage.isAwaitingLight(stage)) {
                out.add(sy);
            }
        });
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /**
     * 收集该 chunk 的待生成 section 连续段，来源是窗口并集或预加载指定集合。
     * 过滤已 TERRAIN 的 section 与可玩范围，clamp 段顶防溢出。
     * 窗口模式额外跳过读回在途的 section，读回完成回调会入队。
     */
    private List<int[]> collectSegments() {
        List<int[]> segments = new ArrayList<>();
        int[] curMin = { 0 };
        int[] curMax = { -1 };
        boolean[] open = { false };
        IntConsumer consider = sy -> {
            if (!WorldBounds.inSection(sy)) {
                return;
            }
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.TERRAIN)) {
                return;
            }
            if (preloadSections == null && SectionIO.isReading(chunk.getPos().pack(), sy)) {
                return;
            }
            if (open[0] && sy == curMax[0] + 1) {
                curMax[0] = sy;
            } else {
                if (open[0]) {
                    segments.add(new int[] { curMin[0], curMax[0] });
                }
                curMin[0] = sy;
                curMax[0] = sy;
                open[0] = true;
            }
        };
        if (preloadSections != null) {
            for (int sy : preloadSections) {
                consider.accept(sy);
            }
        } else {
            EntitySectionWindow.forEachSectionInAnyWindow(consider);
        }
        if (open[0]) {
            segments.add(new int[] { curMin[0], curMax[0] });
        }
        return segments;
    }
}
