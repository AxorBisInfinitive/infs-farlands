package com.inf.farlands.light;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongPriorityQueues;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.util.map.Long2ObjectStripedMap;
import com.inf.farlands.util.window.PlayerTable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * 服务端光照传播任务队列，per-chunk 并行。
 *
 * 方块变化/section 变化/chunk 点亮按 chunk 合并为 {@link ChunkWork}，主线程只入队，
 * 后台传播线程池按 chunk 取任务执行。{@code onComplete} 供生成管线/发送前等待。
 *
 * 线程安全：{@code workMap} 用自研分段 map，computeIfAbsent 原子创建，
 * 每个 ChunkWork 的 isQueued/isExecuting 状态用该任务对象自身同步；
 * {@code pendingWork} 用 fastutil synchronized 队列。入队/取可在任意线程。
 */
public final class FarLandsLightQueue {

    /**
     * 任务期间保持 chunk 加载的 ticket，半径 0，只保加载不卸载，不强制生成。
     *
     * 26.1.2：TicketType 变为 record（timeout, flags），无 mod 侧静态 create 入口，
     * 类型须注册进 BuiltInRegistries.TICKET_TYPE。
     * NO_TIMEOUT + FLAG_LOADING = 只保加载（对齐 1.21.1 的 radius 0 语义）。
     *
     * 注册时机：本类 {@code <clinit>} 是懒加载的（第一次用到它是开世界构造
     * FarLandsLightEngine），那时 BuiltInRegistries 已被 BuiltInRegistries.bootStrap()
     * 的 freeze() 冻死，注册抛 "Registry is already frozen"；mod 初始化期同样在
     * Bootstrap.bootStrap() 之后，也来不及。故由 BuiltInRegistriesMixin 在
     * bootStrap() 头部调 ensureTicketTypeRegistered()，把本类 {@code <clinit>}
     * 提前到冻结点之前——类初始化恰好一次，注册无需额外幂等守卫。
     */
    public static final TicketType CHUNK_WORK_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            InfsFarlands.id("light_chunk_work"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

    /** 触发本类 {@code <clinit>}（即上面的注册）。仅供 BuiltInRegistriesMixin 在冻结前调用。 */
    public static TicketType ensureTicketTypeRegistered() {
        return CHUNK_WORK_TICKET;
    }

    /** chunk key -> 未完成任务。任务执行时被 takeTask 移除；重新入队经 requeue 放回。 */
    private final Long2ObjectStripedMap<ChunkWork> workMap = new Long2ObjectStripedMap<>(1 << 12);
    private final LongPriorityQueue pendingWork = LongPriorityQueues.synchronize(new LongArrayFIFOQueue());
    /** chunk key -> 未完成 light 任务引用计数，决定 ticket 生命周期，见 addWorkRef/removeWorkRef。 */
    private final Long2ObjectStripedMap<AtomicInteger> workRefs = new Long2ObjectStripedMap<>(1 << 10);

    public boolean isEmpty() {
        return workMap.isEmpty();
    }

    public boolean hasWork() {
        return !pendingWork.isEmpty();
    }

    // enqueue：任意线程，线程安全

    public ChunkWork queueBlockChange(BlockPos pos) {
        return mergeWork(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4),
                tasks -> tasks.blockChanges.add(pos.immutable()));
    }

    public ChunkWork queueSectionChange(SectionPos pos, boolean isEmpty) {
        return mergeWork(ChunkPos.pack(pos.x(), pos.z()), tasks -> {
            if (tasks.sectionChanges == null) {
                tasks.sectionChanges = new HashMap<>();
            }
            tasks.sectionChanges.put(pos.y(), isEmpty);
        });
    }

    public ChunkWork queueChunkLighting(ChunkPos pos, Runnable lightTask) {
        return mergeWork(ChunkPos.pack(pos.x(), pos.z()), tasks -> {
            if (tasks.seedTasks == null) {
                tasks.seedTasks = new ArrayList<>();
            }
            tasks.seedTasks.add(lightTask);
        });
    }

    public ChunkWork queueChunkUnload(ChunkPos pos) {
        return mergeWork(ChunkPos.pack(pos.x(), pos.z()), tasks -> tasks.isUnload = true);
    }

    /**
     * 合并入队：同 chunk 已存在任务则并入。正在执行的任务已被 takeTask 移除，
     * computeIfAbsent 会新建 retry 循环不冲突，因为旧任务 isExecuting 时重试取到新任务。
     */
    private ChunkWork mergeWork(long key, Consumer<ChunkWork> action) {
        while (true) {
            ChunkWork tasks = workMap.computeIfAbsent(key, ChunkWork::new);
            synchronized (tasks) {
                if (tasks.isExecuting) {
                    continue;
                }
                action.accept(tasks);
                if (!tasks.isQueued) {
                    tasks.isQueued = true;
                    pendingWork.enqueue(key);
                }
                return tasks;
            }
        }
    }

    // consume：调度，主线程串行

    /** 取下一个有任务的 chunk key；无则 Long.MIN_VALUE。 */
    public long nextDirty() {
        synchronized (pendingWork) {
            if (pendingWork.isEmpty()) {
                return Long.MIN_VALUE;
            }
            long key = pendingWork.dequeueLong();
            return key;
        }
    }

    /**
     * 玩家位置变化 -> 队列按距本维度最近玩家距离重排，近的先处理；原 FIFO 远处先入队先处理，
     * 玩家附近光照延后。无分配 comparator，ChunkPos.getX/Z 为静态。与 nextDirty 同锁互斥。
     * 动态收集用 LongArrayList 不用 size() 预分配数组，并发入队时 size 竞态 -> 越界/
     * 少放回 -> 任务丢 -> inFlight 永久 true -> §5 滞留 -> 空缺。
     * 优先级 = 距任务所在维度（本 engine 绑定 level）最近玩家的 Chebyshev 距离 多玩家
     * 语义与 GenTask.computePriority 一致（去单玩家快照，多人兼容追踪）。
     */
    public void rebuildQueue(ServerLevel level) {
        synchronized (pendingWork) {
            if (pendingWork.isEmpty()) {
                return;
            }
            LongArrayList keys = new LongArrayList();
            while (!pendingWork.isEmpty()) {
                keys.add(pendingWork.dequeueLong());
            }
            long[] arr = keys.toLongArray();
            LongArrays.quickSort(arr, 0, arr.length,
                    (a, b) -> Integer.compare(nearestPlayerDist(level, a), nearestPlayerDist(level, b)));
            for (long k : arr) {
                pendingWork.enqueue(k);
            }
        }
    }

    /** 任务 chunk 到本维度界内最近玩家的 Chebyshev 距离；无界内玩家时给 0，退化成 FIFO。 */
    private static int nearestPlayerDist(ServerLevel level, long chunkKey) {
        return PlayerTable.nearestDistance(level.dimension(), ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    /** 从 map 取走任务并标记执行中。 */
    public ChunkWork takeTask(long key) {
        ChunkWork tasks = workMap.remove(key);
        if (tasks == null) {
            return null;
        }
        synchronized (tasks) {
            tasks.isExecuting = true;
        }
        return tasks;
    }

    /** 锁冲突时放回队列，下轮重试。若执行期间已有同 chunk 新任务，合并内容，不覆盖。 */
    public void requeue(ChunkWork tasks) {
        synchronized (tasks) {
            tasks.isExecuting = false;
            tasks.isQueued = true;
        }
        ChunkWork existing = workMap.putIfAbsent(tasks.chunkKey, tasks);
        if (existing != null) {
            synchronized (existing) {
                existing.blockChanges.addAll(tasks.blockChanges);
                if (tasks.sectionChanges != null) {
                    if (existing.sectionChanges == null) {
                        existing.sectionChanges = new HashMap<>();
                    }
                    existing.sectionChanges.putAll(tasks.sectionChanges);
                }
                if (tasks.seedTasks != null) {
                    if (existing.seedTasks == null) {
                        existing.seedTasks = new ArrayList<>();
                    }
                    existing.seedTasks.addAll(tasks.seedTasks);
                }
                existing.isUnload |= tasks.isUnload;
                if (!existing.isQueued) {
                    existing.isQueued = true;
                    pendingWork.enqueue(existing.chunkKey);
                }
                // 修复 requeue 合并丢 onComplete：tasks 即本任务被合并进 existing 后丢弃
                // 但等 tasks.onComplete 的 lightChunk future 经 fillFrom ->lightChunk ->
                // whenComplete ->LIGHTDONE ->inFlight 释放 ->§5 放行链，没人 complete -> 永久卡
                // -> §5 滞留 -> 空缺
                // "最后只剩几个"。existing 执行完成时链式 complete tasks.onComplete。
                existing.onComplete.whenComplete((v, t) -> tasks.onComplete.complete(null));
            }
        } else {
            pendingWork.enqueue(tasks.chunkKey);
        }
    }

    /** 按 chunk 查询未完成任务，发送前等待光照完成用。 */
    public CompletableFuture<Void> getChunkSyncFuture(int chunkX, int chunkZ) {
        ChunkWork tasks = workMap.get(ChunkPos.pack(chunkX, chunkZ));
        return tasks == null ? CompletableFuture.completedFuture(null) : tasks.onComplete;
    }

    // ticket 引用计数

    /** 任务入队后 +1，返回新计数，==1 表示首个任务，应加 ticket。 */
    public int addWorkRef(long key) {
        return workRefs.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 任务完成后 -1，返回新计数，<=0 表示无剩余任务，应移除 ticket。 */
    public int removeWorkRef(long key) {
        AtomicInteger refs = workRefs.get(key);
        if (refs == null) {
            return 0;
        }
        int v = refs.decrementAndGet();
        if (v <= 0) {
            workRefs.remove(key);
        }
        return v;
    }

    // ChunkWork

    public static final class ChunkWork {
        public final long chunkKey;
        public final Set<BlockPos> blockChanges = new ObjectOpenHashSet<>();
        /** 绝对 sectionY -> 是否空。null = 无变化。 */
        public Map<Integer, Boolean> sectionChanges;
        public List<Runnable> seedTasks;
        public boolean isUnload;
        public boolean isQueued;
        public boolean isExecuting;
        public boolean isTicketAdded;
        public final CompletableFuture<Void> onComplete = new CompletableFuture<>();

        public ChunkWork(long chunkKey) {
            this.chunkKey = chunkKey;
        }

        public int chunkX() {
            return ChunkPos.getX(this.chunkKey);
        }

        public int chunkZ() {
            return ChunkPos.getZ(this.chunkKey);
        }
    }
}
