package com.inf.farlands.simulation.serialize;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.storage.LevelStorageSource;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.model.SimulationConfig;

/**
 * ssa 异步 IO 层。与 fsa 的 {@code SectionIO} 同形，只服务仿真段。
 *
 * <p>
 * 文件缓存与状态全部在主线程，{@link SimulationStorage} 也由主线程独占。IO 线程只做纯 file 读写
 * doWrite、readData、writePages、close，不碰文件状态。提交 API 由主线程调用，即主线程 getOrOpen 拿到
 * storage 后提交 IO 任务，闭包引用该 storage。
 *
 * <p>
 * 两段式写：主线程 prepareWrite 做分配，提交 IO 的 doWrite，成功后回调主线程 commitWrite 写偏移、脏页与
 * 释放旧扇区。写失败不回调，也就不 commit，内存保留，由调用方重试。
 *
 * <p>
 * LRU 淘汰时先移出缓存，把偏移表脏页刷掉，再往 IO 队列排 close 任务，队列的顺序保证该文件的 pending 写
 * 先完成再 close。
 *
 * <p>
 * 路径是 {@code <维度目录>/ssa/s.<16 位十六进制>.ssa}。编号不来自坐标，来自同目录的 {@code mapping.json}：
 * 坐标直接编进文件名会随位宽增长，段号拓到 1024 位时一段就三百多位，路径必然越限。映射表与它的四条不变式见
 * {@link SimulationFileIndex}。
 *
 * <p>
 * 关服序列由调用方排：{@link #awaitIODrain} 排空 IO 队列，{@link #drainMainThreadTasks} 消费 commit 回调，
 * {@link #flushAllSync} 同步刷偏移表，{@link #clearCache} 关句柄。四步的顺序与理由见各方法。
 */
public final class SimulationIO {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "farlands-ssa-io");
        t.setDaemon(true);
        return t;
    });

    /** 主线程独占。LinkedHashMap 访问序，即 LRU，getOrOpen 会提升命中项。 */
    private static final Map<Path, SimulationStorage> cache = new LinkedHashMap<>(
            SimulationConfig.ssaCacheLimit, 0.75f, true);

    /** 一个待读槽位：段的三轴段号，加它在文件里的引用。 */
    public record SlotKey(long sectionX, long sectionY, long sectionZ, SimulationStorage.SlotRef ref) {
    }

    /** 一份读回结果：段号加解码内容。 */
    public record DecodedEntry(long sectionX, long sectionY, long sectionZ,
            SimulationSerializer.DecodedSection decoded) {
    }

    private static final Field F_STORAGE_SOURCE;
    private static final Field F_MAIN_EXECUTOR;
    private static final Method M_POLL_TASK;

    static {
        try {
            F_STORAGE_SOURCE = MinecraftServer.class.getDeclaredField("storageSource");
            F_STORAGE_SOURCE.setAccessible(true);
            F_MAIN_EXECUTOR = ChunkMap.class.getDeclaredField("mainThreadExecutor");
            F_MAIN_EXECUTOR.setAccessible(true);
            // 26.1.2 的 pollTask 是 protected，跨包只能反射
            M_POLL_TASK = net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredMethod("pollTask");
            M_POLL_TASK.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SimulationIO() {
    }

    // ---- 路径 ----

    @SuppressWarnings("resource")
    static Path dimensionDir(ServerLevel level) {
        try {
            LevelStorageSource.LevelStorageAccess access = (LevelStorageSource.LevelStorageAccess) F_STORAGE_SOURCE
                    .get(level.getServer());
            return access.getDimensionPath(level.dimension()).resolve("ssa");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 该段的文件路径，只查映射表，不分配。
     *
     * <p>
     * 该区域还没有 ssa 文件时返回 null。读路必须用它：若在这里分配编号，视距探测会给「还没有任何数据的区域」
     * 建出映射条目，表就会按访问过的区域增长，而不是按有文件的区域。
     */
    public static Path findPath(ServerLevel level, long sectionX, long sectionY, long sectionZ) {
        String id = SimulationFileIndex.of(level).find(regionOf(sectionX, sectionY, sectionZ));
        return id == null ? null : dimensionDir(level).resolve(fileName(id));
    }

    /**
     * 该段的文件路径，必要时分配编号并落盘。只在写路调用。
     *
     * <p>
     * 映射表这一轮没写出去时返回 null：调用方跳过这一段，不建文件、不读盘，等下一次周期刷盘重新分配。
     * 不在这里抛，是因为这个失败点落在服务端 tick 里，一次瞬时的文件拒绝不该终止服务器。
     */
    public static Path pathForWrite(ServerLevel level, long sectionX, long sectionY, long sectionZ) {
        String id = SimulationFileIndex.of(level).ensure(regionOf(sectionX, sectionY, sectionZ));
        if (id == null) {
            return null;
        }
        return dimensionDir(level).resolve(fileName(id));
    }

    /**
     * 段号到区域号的换算，全仓只有这一处。
     *
     * <p>
     * 一个 ssa 文件覆盖 32 个段，所以区域号是段号右移五位。读、落盘、关服三条路各写一遍就是三次犯错机会。
     */
    static SimulationFileIndex.Region regionOf(long sectionX, long sectionY, long sectionZ) {
        return new SimulationFileIndex.Region(sectionX >> 5, sectionY >> 5, sectionZ >> 5);
    }

    /** ssa 文件名：编号来自映射表，恒 26 个字符。 */
    public static String fileName(String id) {
        return "s." + id + ".ssa";
    }

    /**
     * 该段在文件里的槽位索引。形参顺序是 x、z、y，与格式层同规。
     *
     * <p>
     * 低位掩码在 long 上做再落成 int，段号超出 int 时低五位仍然对。
     */
    public static int slotIndex(long sectionX, long sectionY, long sectionZ) {
        return SimulationStorage.slotIndex((int) (sectionX & 31), (int) (sectionZ & 31), (int) (sectionY & 31));
    }

    // ---- 主线程：文件缓存 ----

    public static SimulationStorage getOrOpen(Path path) {
        SimulationStorage storage = cache.get(path);
        if (storage != null) {
            return storage;
        }
        try {
            Files.createDirectories(path.getParent());
            storage = SimulationStorage.open(path, false);
            cache.put(path, storage);
            if (cache.size() > SimulationConfig.ssaCacheLimit) {
                // 淘汰最旧。必须先刷偏移表脏页，否则被淘汰文件的偏移表永不落盘，重进时 getSlot 读到陈旧
                // offset 就会丢段。随后排 close 任务，IO 队列顺序保证该文件 pending 写先执行完。
                Path oldest = cache.keySet().iterator().next();
                SimulationStorage old = cache.remove(oldest);
                if (old != null) {
                    List<SimulationStorage.PageWrite> pages = old.flushAggregate();
                    if (!pages.isEmpty()) {
                        submitWritePages(oldest, pages);
                    }
                    submit(() -> {
                        try {
                            old.close();
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            return storage;
        } catch (Exception e) {
            throw new RuntimeException("ssa open " + path, e);
        }
    }

    /**
     * 主线程：该段在盘上的槽位引用，文件不存在或槽位为空返回 null。
     *
     * <p>
     * 只查偏移表，不读盘，不做 IO，供读回判定用。
     */
    public static SimulationStorage.SlotRef slotAt(ServerLevel level, long sectionX, long sectionY, long sectionZ) {
        Path path = findPath(level, sectionX, sectionY, sectionZ);
        if (path == null || !Files.exists(path)) {
            return null;
        }
        SimulationStorage storage = getOrOpen(path);
        return storage.getSlot(slotIndex(sectionX, sectionY, sectionZ));
    }

    // ---- 提交：主线程调用，IO 线程执行 ----

    public static void submit(Runnable task) {
        try {
            IO.submit(task);
        } catch (RejectedExecutionException e) {
            // 池已关闭，即关服，丢弃
        }
    }

    /**
     * 两段式写的提交端。doWrite 成功后回主线程跑 onAllDone，由调用方 commitWrite。失败则不回调、不
     * commit，pending 丢弃而内存保留，等下次重试。
     */
    public static void submitWrite(ServerLevel level, Path path, List<SimulationStorage.PendingWrite> batch,
            Runnable onAllDone) {
        if (batch.isEmpty()) {
            return;
        }
        SimulationStorage storage = getOrOpen(path);
        submit(() -> {
            try {
                storage.doWrite(batch);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("ssa doWrite failed {}", path, e);
                return; // 失败：不回调，不 commit
            }
            runOnMainThread(() -> {
                if (onAllDone != null) {
                    onAllDone.run();
                }
            }, level);
        });
    }

    /**
     * 批量读回。IO 线程读全部条目并解码，再回主线程回调，只带成功解码的条目。
     *
     * <p>
     * 损坏或读失败的条目跳过，由调用方按不存在处理，即重跑生产者。坐标自检失败也落在这一支：那段数据与
     * 文件名不符，不该被采用。
     */
    public static void submitRead(ServerLevel level, Path path, List<SlotKey> keys,
            PalettedContainerFactory containerFactory, Consumer<List<DecodedEntry>> onFound) {
        if (keys.isEmpty()) {
            runOnMainThread(() -> onFound.accept(List.of()), level);
            return;
        }
        SimulationStorage storage = getOrOpen(path);
        submit(() -> {
            List<DecodedEntry> decoded = new ArrayList<>(keys.size());
            for (SlotKey key : keys) {
                try {
                    byte[] entry = storage.readData(key.ref().sectorOffset(), key.ref().sectorCount());
                    if (entry != null) {
                        SimulationSerializer.DecodedSection d = SimulationSerializer.decode(entry,
                                containerFactory, key.sectionX(), key.sectionY(), key.sectionZ());
                        decoded.add(new DecodedEntry(key.sectionX(), key.sectionY(), key.sectionZ(), d));
                    }
                } catch (Exception e) {
                    // 损坏按不存在处理，跳过
                }
            }
            runOnMainThread(() -> onFound.accept(decoded), level);
        });
    }

    public static void submitWritePages(Path path, List<SimulationStorage.PageWrite> pages) {
        if (pages.isEmpty()) {
            return;
        }
        SimulationStorage storage = getOrOpen(path);
        submit(() -> {
            try {
                storage.writePages(pages);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("ssa writePages failed {}", path, e);
            }
        });
    }

    /**
     * 主线程：把全部缓存文件的偏移表脏页刷盘。运行中的磁盘偏移表一旦陈旧，重进时 getSlot 就会错位丢段，
     * 这里是崩溃与强退的兜底。LRU 淘汰路径另有单独刷盘。
     */
    public static void flushAllOffsetTables() {
        for (Path p : new ArrayList<>(cache.keySet())) {
            SimulationStorage st = cache.get(p);
            if (st == null) {
                continue;
            }
            List<SimulationStorage.PageWrite> pages = st.flushAggregate();
            if (!pages.isEmpty()) {
                submitWritePages(p, pages);
            }
        }
    }

    // ---- 刷盘与关闭 ----

    /** 关服：等 IO 队列排空。屏障任务无超时，IO 是单线程，必然会被执行。 */
    public static void awaitIODrain() {
        CountDownLatch latch = new CountDownLatch(1);
        submit(() -> latch.countDown());
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 关服：排空主线程 pending 任务，消费 commit 回调。单任务异常跳过，不中断整体排空。 */
    public static void drainMainThreadTasks(MinecraftServer server) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                ChunkMap chunkMap = level.getChunkSource().chunkMap;
                Object main = F_MAIN_EXECUTOR.get(chunkMap);
                if (main instanceof net.minecraft.util.thread.BlockableEventLoop<?> loop) {
                    int guard = 0;
                    while (guard++ < 100000) {
                        boolean ran = (Boolean) M_POLL_TASK.invoke(loop);
                        if (!ran) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 反射失败就跳过，属防御
        }
    }

    /** 关服：主线程同步刷盘全部缓存文件。 */
    public static void flushAllSync() {
        for (Path p : new ArrayList<>(cache.keySet())) {
            SimulationStorage st = cache.get(p);
            if (st == null) {
                continue;
            }
            List<SimulationStorage.PageWrite> pages = st.flushAggregate();
            if (pages.isEmpty()) {
                continue;
            }
            try {
                st.writePages(pages);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("ssa flushAllSync failed {}", p, e);
            }
        }
    }

    /**
     * 停服：关掉全部缓存文件并清空缓存，释放上一个世界的句柄与内存偏移表。
     *
     * <p>
     * 必须排在 {@link #flushAllSync} 之后：偏移表脏页在那一步已经同步落盘，而把它标脏的 commitWrite 回调
     * 都在主线程队列里，主线程此刻正停在这段注入里，所以此后不会再有回调标脏。这里仍按淘汰路径的写法做
     * 一次防御：先 flushAggregate 取脏页并同步写掉，再 close。走异步的 submitWritePages 不行，进程可能
     * 马上结束；直接 close 更不行，flushAggregate 会清掉脏标记，页没写就等于永久丢掉偏移表。
     *
     * <p>
     * 不关 IO 执行器：单机换世界时同一个进程还要用它。
     */
    public static void clearCache() {
        for (Path p : new ArrayList<>(cache.keySet())) {
            SimulationStorage st = cache.remove(p);
            if (st == null) {
                continue;
            }
            List<SimulationStorage.PageWrite> pages = st.flushAggregate();
            if (!pages.isEmpty()) {
                try {
                    st.writePages(pages);
                } catch (Exception e) {
                    InfsFarlands.LOGGER.error("ssa clearCache flush failed {}", p, e);
                }
            }
            try {
                st.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- 调度 ----

    /**
     * 把任务调度到主线程，走该 ServerLevel 的 chunkMap 实例上的 ChunkMap.mainThreadExecutor。
     *
     * <p>
     * 它是实例字段，get(null) 会 NPE，导致回调落在 IO 线程上执行。
     */
    @SuppressWarnings("unchecked")
    public static void runOnMainThread(Runnable task, ServerLevel level) {
        try {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object main = F_MAIN_EXECUTOR.get(chunkMap);
            if (main instanceof net.minecraft.util.thread.BlockableEventLoop<?> loop) {
                ((net.minecraft.util.thread.BlockableEventLoop<Runnable>) loop).execute(task);
            } else {
                task.run();
            }
        } catch (Exception e) {
            task.run(); // 反射失败就在当前线程执行，属防御
        }
    }

    /**
     * 当前线程是否是该 level 的主线程执行器所在线程。
     *
     * <p>
     * 提交侧的判据：主线程上内联提交，池线程上 marshal，与 fsa 的 {@code SectionIO.isMainThread} 同形。
     */
    public static boolean isMainThread(ServerLevel level) {
        try {
            Object main = F_MAIN_EXECUTOR.get(level.getChunkSource().chunkMap);
            if (main instanceof net.minecraft.util.thread.BlockableEventLoop<?> loop) {
                return loop.isSameThread();
            }
        } catch (Exception e) {
            // 反射失败按非主线程处理，走 marshal 更保守。
        }
        return false;
    }
}
