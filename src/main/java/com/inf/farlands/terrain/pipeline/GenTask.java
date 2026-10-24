package com.inf.farlands.terrain.pipeline;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.debug.CarveLoopProbe;
import com.inf.farlands.serialize.CounterIdentities;
import com.inf.farlands.serialize.CounterIdentity;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.surfaceFiller.SurfaceFiller;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.PlayerSnapshot;
import com.inf.farlands.util.world.WorldBounds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * chunk 级生成任务，状态转换批。一个任务对应一个 chunk，execute 时收集玩家窗口并集内该
 * chunk 未生成的 section，按连续段分组，每段一个 NoiseChunk 填充，固定成本从每 section 一次
 * 降到每段一次。
 *
 * 瞬态：触发即建，执行即毁。持 LevelChunk 强引用以防 GC。fill 在途由 GenQueue 的 ticket
 * 保加载，chunk 不卸载，光照播种对象始终正确。
 *
 * 并发：同 chunk 至多一个任务在途，由 {@code OwnershipTable} 的 TERRAIN 认领去重，因此 fill 无并发
 * 写高度图。不同 chunk 并行。
 */
public final class GenTask {

    private final LevelChunk chunk;
    /** 预加载模式：指定 section 集合。null 表示窗口模式，用玩家窗口并集。 */
    private final int[] preloadSections;
    /** 该 chunk 到最近玩家的 XZ Chebyshev 距离，入队时快照，玩家移动时 refreshPriority 重算。 */
    private int priority;

    /** 本任务持有的 TERRAIN 认领编号，收尾时带回去释放。 */
    private final long genOwner;

    /** 诊断用：本次收集到的段数与 carve 出的段数。 */
    private int lastSegments;
    private int lastCarved;

    /** 诊断用：派发时领到的全局序号，与 `FLDISP` 那一行按序号对齐。 */
    private final long dispatchSeq;

    public GenTask(LevelChunk chunk, int[] preloadSections, long genOwner, long dispatchSeq) {
        this.chunk = chunk;
        this.preloadSections = preloadSections;
        this.priority = computePriority(chunk);
        this.genOwner = genOwner;
        this.dispatchSeq = dispatchSeq;
    }

    /** 本任务的认领编号。 */
    long genOwner() {
        return this.genOwner;
    }

    /** 本任务的 chunk，诊断行用。 */
    LevelChunk chunk() {
        return this.chunk;
    }

    /** 诊断用：本次执行收集到的段数。 */
    int lastSegments() {
        return this.lastSegments;
    }

    /** 诊断用：本次执行 carve 出的段数。 */
    int lastCarved() {
        return this.lastCarved;
    }

    int priority() {
        return this.priority;
    }

    /** 玩家位置变化时由 GenQueue.rebuildQueue 调用重算，修复快照旧。 */
    void refreshPriority() {
        this.priority = computePriority(this.chunk);
    }

    /** 距该维度最近玩家的距离，读每 tick 的玩家快照。无玩家时返回 0，退化为 FIFO。 */
    private static int computePriority(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel sl) {
            ChunkPos cp = chunk.getPos();
            return PlayerSnapshot.nearestDistance(sl.dimension(), cp.x(), cp.z());
        }
        return 0;
    }

    public void execute() {
        try {
            Level level = chunk.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            List<int[]> segments = collectSegments();
            this.lastSegments = segments.size();
            logTaskEntry(serverLevel, segments.size());
            for (int[] seg : segments) {
                // 先补 biome 再推 TERRAIN。本方法是全流程唯一把 stage 推到 TERRAIN 的地方，放这里就与
                // 入队顺序无关；若让生成先跑，stage 越过 BIOMES，fillSectionBiomes 的 stage < BIOMES 门
                // 会永假，该段 biome 永久停在 PalettedContainerFactory 的默认群系 plains，地表规则随之
                // 按 plains 跑，密度依赖 biome 的系统连形状一起错。
                //
                // 不需要用 BIOME_FILLING 包住这一段：本任务全程持有 TERRAIN 认领，发送侧一律看到
                // isChunkBusy 为真并返回空表，生成期间不会读这一段。那个标志是给短路完成后跑在
                // backgroundExecutor 上的 biome 阶段用的，见 WindowSendState.sendableSections。
                for (int sy = seg[0]; sy <= seg[1]; sy++) {
                    BiomeFiller.fillSectionBiomes(serverLevel, chunk, sy);
                }
                long fillStart = System.nanoTime();
                try {
                    GenQueue.filler(serverLevel).fill(serverLevel, chunk, seg[0], seg[1]);
                } catch (Exception e) {
                    InfsFarlands.LOGGER.error("GENTASK fill ex chunk={},{} {}",
                            chunk.getPos().x(), chunk.getPos().z(), e.toString());
                    throw e;
                }
                StageMetrics.stageWork(StageMetrics.STAGE_TERRAIN, System.nanoTime() - fillStart);
                int[] band = new int[seg[1] - seg[0] + 1];
                for (int i = 0; i < band.length; i++) {
                    band[i] = seg[0] + i;
                }
                Scheduler.stage(chunk, band, SectionStage.TERRAIN);
                Scheduler.dirty(chunk, band, true);
                StageMetrics.stageSections(StageMetrics.STAGE_TERRAIN, band.length);
            }
            // SURFACE 独立于 segments，fill 后紧跟，因为依赖 fill 产出的高度图，也覆盖读回
            // stage 为 TERRAIN 与 surface 失败残留。失败不抛，section 停留 TERRAIN，下一次提交的
            // 准入会重试。本步不触发光照：TERRAIN 的出路就是这里，升格只认 DECORATED。
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
            this.lastCarved = carved.length;
            // 雕刻完成即宣告写域就绪：这一格从低于 CARVERS 变成就绪，九格门要重判。装饰的发现与
            // 提交由调度器在准入时做，本任务不再登记。
            if (carved.length > 0) {
                DecorationFiller.cellChanged((ServerLevel) chunk.getLevel(), chunk.getPos().pack());
            }
            // 光照触发条件：carvers 产出了东西，或已有段到 DECORATED。CARVERS 不在判据内：
            // 那一段的下一步是装饰而不是光照，promote 也不升它，算进来只会每 tick 白跑一次光照。
            int[] awaitingLight = awaitingLightSections();
            if (carved.length > 0 || awaitingLight.length > 0) {
                // 恢复的段不脏，applyDecoded 只恢复 stage。不标脏则 persistChunkDirty 写不到它，
                // stage 会一直停在盘上的旧值，每次重进都要重算一遍光照。
                // 这一处没有伴随档位变化，单独投一条脏事件，走落档体。
                Scheduler.dirty(chunk, awaitingLight, true);
                // 交准入：光照由调度器的第六行发，本任务只把该 chunk 推回候选表。
                Scheduler.submit("task", chunk);
                // fill、surface、carvers 直接写 section，没有 vanilla 广播；这里只标记该 chunk
                // 内容已变，由 ChunkDataSender 每 tick 按玩家当前窗口物化后再发段包。光照会先完成，
                // flush 时 LIGHTED 认领为真，于是留队列等光照完成，方块与光照同到。
                ChunkDataSender.markChunkChanged(chunk);
            }
        } finally {
            // fill 异常也清理：释放本任务持有的认领与票。异常路径若不清理会让认领残留，chunk 永不卸载。
            GenQueue.completeTask(this);
        }
    }

    /**
     * 光照会升、但还没升的 section，即 SectionStage.isAwaitingLight 认的一档：DECORATED。
     * 它是本任务补触发光照的判据；判据的出处只有 SectionStage 那一处，避免与
     * promoteAllGenToLighted 的匹配面失同步。
     *
     * <p>CARVERS 不在判据内：它的出路是装饰，光照不会升它。TERRAIN 与 SURFACE 也不在：
     * 它们的出路是本任务紧跟着的 surface 与 carve 两步。UNPROCESSED 与 BIOMES 更不在：
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
     * 诊断：作业入口一行。临时仪器，与同批探针一并删除。
     *
     * <p>一次给全判断所需的几栏：对象身份与当前 chunk 是否同一个、记账身份是否还活着、容器段带的档位
     * 分布、准入用的段数组长度与其中低于 CARVERS 的个数、本次真正收集到的段数，以及 surface 与 carvers
     * 两道门当场是否成立。
     *
     * <p>限流分两条通道：空转与非空转各按 chunk 每秒一条。空转占了作业的绝大多数，共用一个通道会把
     * 非空转那条挤掉，而判读要的是同一格里两种形态各自的样子。
     */
    private void logTaskEntry(ServerLevel level, int collect) {
        ChunkPos pos = chunk.getPos();
        boolean sur = SurfaceFiller.hasSurfacePending(chunk);
        boolean car = CarverFiller.hasCarversPending(chunk);
        boolean noop = collect == 0 && !sur && !car;
        if (!CarveLoopProbe.shouldLogTask(pos.pack(), noop)) {
            return;
        }
        long counterId = chunk instanceof CounterIdentity identity ? identity.counterId() : 0L;
        LevelChunk current = SectionLifecycle.latestChunk(level, pos.x(), pos.z());
        int[] sections = preloadSections != null ? preloadSections : Scheduler.sectionsFor(chunk);
        int pending = DependencyTable.pendingBelowIn(chunk, sections, SectionStage.CARVERS);
        int reading = 0;
        for (int sy : sections) {
            if (SectionIO.isReading(chunk, sy)) {
                reading++;
            }
        }
        InfsFarlands.LOGGER.info(
                "FLTASK pos={},{} seq={} id={} ctr={} live={} same={} stages={} n={} pk={} rd={} collect={} sur={} car={} noop={}",
                pos.x(), pos.z(), this.dispatchSeq, System.identityHashCode(chunk), counterId,
                CounterIdentities.isLive(counterId), current == chunk,
                stageHistogram(chunk), sections.length, pending, reading, collect, sur, car, noop);
    }

    /** 诊断：容器段带的档位直方图，下标即档位。 */
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

    /**
     * 收集该 chunk 的待生成 section 连续段，来源是窗口并集或预加载指定集合。
     * 过滤已 TERRAIN 的 section 与可玩范围，窗口模式额外跳过读回在途的 section。
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
            // 两种模式都跳过在读段：读回会整体替换段对象，写进旧对象的内容被丢弃，而高度图已被改过。
            // 过滤与 Scheduler.sectionsFor 同源，缺一条就会让准入放行而这里收不到段。
            if (SectionIO.isReading(chunk, sy)) {
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
            EntitySectionWindow.forEachSectionInAnyWindow(chunk.getLevel().dimension(), consider);
        }
        if (open[0]) {
            segments.add(new int[] { curMin[0], curMax[0] });
        }
        return segments;
    }
}
