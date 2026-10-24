package com.inf.farlands.compat.chunky;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsTick;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.pipeline.NeighborhoodTickets;
import com.inf.farlands.terrain.pipeline.Scheduler;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Chunky 1.5.3 兼容层：把它的预生成请求接到本 port 的管线，并把它的完成判据换成本 port 的真话。
 *
 * <p>
 * 它驱动一块只做两件事：铺自己的票，票型是 {@code TicketType(0L, FLAG_LOADING)}，就地构造、不注册
 * 进票型注册表；以及向原版要一个 {@code ChunkStatus.FULL} 的 future。判「这块好了」只认
 * {@code holder.getLatestStatus() == FULL} 或存档 NBT 里的 {@code Status}。
 *
 * <p>
 * 而本 port 的 FULL 是「没有数据在等或在途」的意思：一个没有 stage 的空壳也满足。于是它会把空壳当成
 * 生成完毕跳过，进度走到 100% 而地形一格没跑；而票只让 chunk 加载成壳，够不到本 port 的生成入口。
 *
 * <p>
 * 本层把两边接上：把 {@code isChunkGenerated} 的结论换成「带内段全部 LIGHTED」，把
 * {@code getChunkAtAsync} 的返回 future 换成同一判据的完成信号；等待期间给该 chunk 的 ±8 铺票，见
 * {@link NeighborhoodTickets#hold}，并向调度器登记一个力面，由准入按面驱动。
 *
 * <p>
 * 驱动必须周期性，不能只做一次：第一轮任务常撞上「没有 Beardifier」那道门而早退，而这一项此后既不在
 * 窗口并集里，扫描也够不着，那一条判据对离玩家远的块为假，于是它在 completeTask 里被直接撤票丢项，没有
 * 任何人在重试它。周期重驱动每轮顺带经 StructureDriver 钉一次 ±8，几轮之后门自己会开；preload 幂等，
 * 在途时直接返回。
 *
 * <p>
 * 每一轮还要连中心的 1 环邻居一起驱动：装饰门要中心所在 chunk 的 3×3 都有段且都到 CARVERS，而本 port
 * 的管线不拉邻居，vanilla 的金字塔会。只驱动中心的话，工作集波前的门恒不过、future 永不完成，Chunky 那
 * 50 个并发位被这些块占死，整局退化成每几秒漏过去一块。
 *
 * <p>
 * 段带是段号区间，由 {@code /chunky farlands minY|maxY} 设置，默认 -4 到 19，不读配置、不落盘。
 *
 * <p>
 * 本类不出现任何 Chunky 类型：目标类只在 {@link ChunkyPregenHook} 与
 * {@code mixin/compat/chunky/FabricWorldMixin} 里出现，两者都只在装了 Chunky 时才会被加载。
 */
public final class ChunkyPregen {

    /** 等待 chunk 建出来的上限：超过就放行这一块，不把 Chunky 的并发位挂死。 */
    private static final long CHUNK_WAIT_MS = 30_000L;

    /** 从请求到带内全亮的兜底上限：超时即撤票并放行，宁可下一轮再要一次，也不永久占住票与并发位。 */
    private static final long HOLD_TIMEOUT_MS = 300_000L;

    /**
     * 未点亮时两次驱动之间的间隔，取一个 tick。
     *
     * <p>
     * 撞门的项要靠它一轮轮重试，见类注释。取到一个 tick 就是把它压到最接近事件驱动：每轮对九格各做一次
     * 取数，重复的 preload 由在途标志挡掉，所以代价只是取数本身。
     */
    private static final long DRIVE_RETRY_MS = 50L;

    /** 预生成的段带。命令在主线程写，判据可能在别的线程读，所以是 volatile。 */
    private static volatile int minSection = -4;
    private static volatile int maxSection = 19;

    private record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    /** 一次预生成请求。done 是交回 Chunky 的 future。 */
    private static final class Request {
        private final ServerLevel level;
        private final ChunkPos pos;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final long since = System.currentTimeMillis();
        /** 本次请求自己铺下的票的位置。撤票只撤这些，别人的票不动。 */
        private final List<ChunkPos> held = new ArrayList<>();
        /** 本次登记给调度器的力面，收尾时撤。 */
        private Scheduler.Face face;
        /** 下次驱动的时刻。0 表示还没驱动过，第一次扫到即驱动。 */
        private long nextDriveAt;

        private Request(ServerLevel level, ChunkPos pos) {
            this.level = level;
            this.pos = pos;
        }
    }

    private static final Map<Key, Request> WAITING = new ConcurrentHashMap<>();

    private ChunkyPregen() {
    }

    /** 模组初始化期调用。装了 Chunky 才登记命令、每 tick 钩子与读数行。 */
    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("chunky")) {
            return;
        }
        ChunkyPregenHook.registerCommands();
        FarlandsTick.addTickSink(ChunkyPregen::tick);
    }

    /** 段带下界，段号。越界或大于上界即拒绝。 */
    public static boolean setMinSection(int value) {
        if (value > maxSection || !WorldBounds.inSectionAbsolute(value)) {
            return false;
        }
        minSection = value;
        return true;
    }

    /** 段带上界，段号。越界或小于下界即拒绝。 */
    public static boolean setMaxSection(int value) {
        if (value < minSection || !WorldBounds.inSectionAbsolute(value)) {
            return false;
        }
        maxSection = value;
        return true;
    }

    public static int minSection() {
        return minSection;
    }

    public static int maxSection() {
        return maxSection;
    }

    /**
     * 一次预生成请求的开始。只在服务端线程调用，调用点判过线程。
     *
     * <p>
     * 同一块被重复问时共用同一个 future：重复铺票与重复驱动都无意义。
     */
    public static CompletableFuture<Void> begin(ServerLevel level, ChunkPos pos) {
        Key key = new Key(level.dimension(), pos.pack());
        Request request = new Request(level, pos);
        Request prev = WAITING.putIfAbsent(key, request);
        if (prev != null) {
            return prev.done;
        }
        if (NeighborhoodTickets.hold(level, pos)) {
            request.held.add(pos);
        }
        // 登记力面：调度器的准入按面算驱动范围与段范围，本类不再自己驱动。
        request.face = new Scheduler.Face(level.dimension(), pos.x(), pos.z(), 1, minSection, maxSection);
        Scheduler.register(request.face);
        return request.done;
    }

    /** 带内是否全部点亮。只有 {@code latestChunk} 要求主线程，其余读的都是并发结构。 */
    public static boolean bandLit(ServerLevel level, int x, int z) {
        LevelChunk chunk = SectionLifecycle.latestChunk(level, x, z);
        return chunk != null && (bandState(chunk) & BAND_LIT) != 0;
    }

    /** 带内全部 ≥ CARVERS，即生成侧的活已经做完。 */
    private static final int BAND_CARVED = 1;

    /** 带内全部 ≥ LIGHTED。 */
    private static final int BAND_LIT = 2;

    /**
     * 一次遍历同时判定两件事：带内是否全部过 CARVERS、是否全部过 LIGHTED。
     *
     * <p>
     * 一次遍历而不是两次：这个扫描每个请求每 tick 一遍，宽度 168 时它是主线程上最大的一笔固定开销，
     * 拆成两遍就是把它翻倍。判据与读数的口径都从这一处出，也不会两处失同步。
     */
    private static int bandState(LevelChunk chunk) {
        Map<Integer, LevelChunkSection> all = ((WindowedChunk) chunk).windowedAllSections();
        int state = BAND_CARVED | BAND_LIT;
        for (int sy = minSection; sy <= maxSection; sy++) {
            LevelChunkSection section = all.get(sy);
            int stage = section == null ? SectionStage.UNPROCESSED : SectionStage.getStage(chunk, sy);
            if (stage < SectionStage.CARVERS) {
                state &= ~BAND_CARVED;
            }
            if (stage < SectionStage.LIGHTED) {
                state &= ~BAND_LIT;
            }
            if (state == 0) {
                break;
            }
        }
        return state;
    }

    /** 每 tick 由 {@code FarlandsTick} 的钩子在主线程调用：驱动待办、检查带内、超时兜底。 */
    public static void tick(MinecraftServer server) {
        if (WAITING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, Request> entry : WAITING.entrySet()) {
            Request request = entry.getValue();
            LevelChunk chunk = SectionLifecycle.latestChunk(request.level, request.pos.x(), request.pos.z());
            if (chunk == null) {
                if (now - request.since > CHUNK_WAIT_MS) {
                    finish(entry.getKey(), request);
                }
                continue;
            }
            int state = bandState(chunk);
            if (now >= request.nextDriveAt) {
                request.nextDriveAt = now + DRIVE_RETRY_MS;
                // 续票并把 3×3 提交给调度器：面只给几何，候选仍要有人提交。段带由面查出来交给作业。
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        ChunkPos target = new ChunkPos(request.pos.x() + dx, request.pos.z() + dz);
                        if (NeighborhoodTickets.hold(request.level, target)) {
                            request.held.add(target);
                        }
                        LevelChunk loaded = dx == 0 && dz == 0 ? chunk
                                : SectionLifecycle.latestChunk(request.level, target.x(), target.z());
                        if (loaded != null) {
                            Scheduler.submit("exist", loaded);
                        }
                    }
                }
            }
            if ((state & BAND_LIT) != 0) {
                finish(entry.getKey(), request);
            } else if (now - request.since > HOLD_TIMEOUT_MS) {
                finish(entry.getKey(), request);
            }
        }
    }

    /** 收尾：出表、撤面、撤本次自己铺的票、放行 future。 */
    private static void finish(Key key, Request request) {
        WAITING.remove(key);
        if (request.face != null) {
            Scheduler.unregister(request.face);
        }
        for (ChunkPos held : request.held) {
            NeighborhoodTickets.release(request.level, held);
        }
        request.held.clear();
        request.done.complete(null);
    }
}
