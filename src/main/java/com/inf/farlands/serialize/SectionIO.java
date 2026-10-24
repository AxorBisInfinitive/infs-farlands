package com.inf.farlands.serialize;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * fsa 异步 IO 层。
 *
 * 文件缓存与状态全部在主线程，SectionStorage 也由主线程独占。IO 线程只做纯 file 读写
 * doWrite/readData/writePages/close，不碰文件状态。提交 API 由主线程调用，即主线程
 * getOrOpen 拿到 storage 后提交 IO 任务，闭包引用该 storage。
 *
 * 两段式写：主线程 prepareWrite 做 alloc，提交 IO 的 doWrite，成功后回调主线程
 * commitWrite 写 offsets、脏页与释放旧扇区。写失败不回调，也就不 commit，内存保留，
 * dirty 重试。
 *
 * LRU 淘汰时先移出缓存，再往 IO 队列排 close 任务，队列的顺序保证该文件的 pending 写
 * 先完成再 close。
 *
 * 读回判定走主线程 getSlot，offsets 即时可见，不依赖 IO 队列。
 *
 * 26.1.2 相对 1.21.1 的差异，均已 javap 打运行时 jar 核实。MinecraftServer.storageSource
 * 与 ChunkMap.mainThreadExecutor 字段名不变，反射照旧。BlockableEventLoop.pollTask 由
 * public 变 protected，跨包调用必须反射。ChunkPos.toLong 改名 pack。条目的
 * block_states 与 biomes codec 不再反射 ChunkSerializer，该类在 26.1.2 已消失，改由 chunk
 * 的 PalettedContainerFactory 提供，因此读回入口多一个 factory 参数，同时不再需要
 * registryAccess().registryOrThrow(Registries.BIOME)。
 */
public final class SectionIO {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "farlands-fsa-io");
        t.setDaemon(true);
        return t;
    });

    /** 主线程独占。LinkedHashMap 访问序，即 LRU，getOrOpen 会提升命中项。 */
    private static final Map<Path, SectionStorage> cache = new LinkedHashMap<>(FarlandsConfig.fsaCacheLimit, 0.75f,
            true);

    /** 读回在途的载体已搬进 {@link SectionState#READING}，本类只做转调。 */
    public record DecodedWithSy(int sectionY, SectionSerializer.DecodedSection decoded) {
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

    private SectionIO() {
    }

    // ---- 路径 ----

    @SuppressWarnings("resource")
    private static Path dimensionDir(ServerLevel level) {
        try {
            LevelStorageSource.LevelStorageAccess access = (LevelStorageSource.LevelStorageAccess) F_STORAGE_SOURCE
                    .get(level.getServer());
            return access.getDimensionPath(level.dimension()).resolve("fsa");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** s.{regionX}.{yBlock}.{regionZ}.fsa，yb 是绝对 sectionY 右移 5。 */
    public static Path filePath(ServerLevel level, int cx, int cz, int sy) {
        return dimensionDir(level).resolve(fileName(cx << 4, sy << 4, cz << 4));
    }

    /**
     * fsa 文件名 s.{regionX}.{yBlock}.{regionZ}.fsa，形参依次是 x、y、z，且都是方块坐标。
     *
     * <p>
     * 三段与 filePath 写出的完全一致：regionX = chunkX 右移 5、yBlock = sectionY 右移 5、
     * regionZ = chunkZ 右移 5。以方块坐标作入参，三段就是同一入参各右移 9，不在调用点做 chunk 与
     * section 的混搭换算。filePath 手上的 chunk 坐标左移 4 即方块坐标。
     *
     * <p>
     * 纯字符串、不依赖 ServerLevel，客户端侧的诊断行也能算出同一个名字。
     */
    public static String fileName(int x, int y, int z) {
        return "s." + (x >> 9) + "." + (y >> 9) + "." + (z >> 9) + ".fsa";
    }

    // ---- 主线程：文件缓存 ----

    public static SectionStorage getOrOpen(Path path) {
        SectionStorage storage = cache.get(path);
        if (storage != null) {
            return storage;
        }
        try {
            Files.createDirectories(path.getParent());
            storage = SectionStorage.open(path, false);
            cache.put(path, storage);
            if (cache.size() > FarlandsConfig.fsaCacheLimit) {
                // 淘汰最旧。必须先刷偏移表脏页，否则被淘汰文件的偏移表永不落盘，重进时
                // getSlot 读到陈旧 offset 就会丢 section。随后排 close 任务，IO 队列顺序
                // 保证该文件 pending 写先执行完。
                Path oldest = cache.keySet().iterator().next();
                SectionStorage old = cache.remove(oldest);
                if (old != null) {
                    List<SectionStorage.PageWrite> pages = old.flushAggregate();
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
            throw new RuntimeException("fsa open " + path, e);
        }
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
     * 两段式写的提交端。doWrite 成功后回主线程跑 onAllDone，由调用方 commitWrite。
     * 失败则不回调、不 commit，pending 丢弃而内存保留，等 dirty 重试。
     */
    public static void submitWrite(ServerLevel level, Path path, List<SectionStorage.PendingWrite> batch,
            Runnable onAllDone) {
        if (batch.isEmpty()) {
            return;
        }
        SectionStorage storage = getOrOpen(path);
        submit(() -> {
            try {
                storage.doWrite(batch);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("fsa doWrite failed {}", path, e);
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
     * 批量读回。IO 线程读全部条目并 decode，再回主线程回调，只带成功解码的条目及其
     * sectionY。损坏或读失败的条目跳过，由调用方按不存在处理。
     *
     * codec 来自调用方，即 chunk 的 PalettedContainerFactory。26.1.2 的 block_states 与
     * biomes codec 内嵌在 factory 里，不再反射 ChunkSerializer。
     */
    public static void submitRead(ServerLevel level, Path path, List<SectionStorage.SlotRef> refs,
            PalettedContainerFactory containerFactory, Consumer<List<DecodedWithSy>> onFound) {
        if (refs.isEmpty()) {
            runOnMainThread(() -> onFound.accept(List.of()), level);
            return;
        }
        SectionStorage storage = getOrOpen(path);
        submit(() -> {
            List<DecodedWithSy> decoded = new ArrayList<>(refs.size());
            for (SectionStorage.SlotRef ref : refs) {
                try {
                    byte[] entry = storage.readData(ref.sectorOffset(), ref.sectorCount());
                    if (entry != null) {
                        SectionSerializer.DecodedSection d = SectionSerializer.decode(entry, containerFactory,
                                ref.sectionY());
                        decoded.add(new DecodedWithSy(ref.sectionY(), d));
                    }
                } catch (Exception e) {
                    // 损坏按不存在处理，跳过
                }
            }
            runOnMainThread(() -> onFound.accept(decoded), level);
        });
    }

    public static void submitWritePages(Path path, List<SectionStorage.PageWrite> pages) {
        if (pages.isEmpty()) {
            return;
        }
        SectionStorage storage = getOrOpen(path);
        submit(() -> {
            try {
                storage.writePages(pages);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("fsa writePages failed {}", path, e);
            }
        });
    }

    /**
     * 主线程：把全部缓存文件的偏移表脏页刷盘。运行中的磁盘偏移表一旦陈旧，重进时
     * getSlot 就会错位丢 section，这里是崩溃与强退的兜底。LRU 淘汰路径另有单独刷盘。
     */
    public static void flushAllOffsetTables() {
        for (Path p : new ArrayList<>(cache.keySet())) {
            SectionStorage st = cache.get(p);
            if (st == null) {
                continue;
            }
            List<SectionStorage.PageWrite> pages = st.flushAggregate();
            if (!pages.isEmpty()) {
                submitWritePages(p, pages);
            }
        }
    }

    // ---- 刷盘 / 关闭 ----

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
            SectionStorage st = cache.get(p);
            if (st == null) {
                continue;
            }
            List<SectionStorage.PageWrite> pages = st.flushAggregate();
            if (pages.isEmpty()) {
                continue;
            }
            try {
                st.writePages(pages);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("fsa flushAllSync failed {}", p, e);
            }
        }
    }

    /**
     * 停服：关掉全部缓存文件并清空缓存，释放上一个世界的句柄与内存偏移表。
     *
     * <p>必须排在 flushAllSync 之后：偏移表脏页在那一步已经同步落盘，而把它标脏的 commitWrite 回调都在
     * 主线程队列里，主线程此刻正停在这段注入里，所以此后不会再有回调标脏。这里仍按淘汰路径的写法做一次
     * 防御：先 flushAggregate 取脏页并同步写掉，再 close。走异步的 submitWritePages 不行，进程可能马上
     * 结束；直接 close 更不行，flushAggregate 会清掉脏标记，页没写就等于永久丢掉偏移表。
     *
     * <p>不关 IO 执行器：单机换世界时同一个进程还要用它。
     */
    public static void clearCache() {
        for (Path p : new ArrayList<>(cache.keySet())) {
            SectionStorage st = cache.remove(p);
            if (st == null) {
                continue;
            }
            List<SectionStorage.PageWrite> pages = st.flushAggregate();
            if (!pages.isEmpty()) {
                try {
                    st.writePages(pages);
                } catch (Exception e) {
                    InfsFarlands.LOGGER.error("fsa clearCache flush failed {}", p, e);
                }
            }
            try {
                st.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- 读回在途，载体在 SectionState ----

    public static boolean isReading(LevelChunk chunk, int sectionY) {
        SectionState state = SectionStage.stateOrNull(chunk, sectionY);
        return state != null && state.isReading();
    }

    /** 该 chunk 是否有读回在途。只回答有无。 */
    public static boolean isReadingAny(LevelChunk chunk) {
        return SectionStage.anyReading(chunk);
    }

    public static void markReading(LevelChunk chunk, int sectionY) {
        SectionState state = SectionStage.stateOf(chunk, sectionY);
        if (state != null) {
            state.markReading();
        }
    }

    public static void markReadingBatch(LevelChunk chunk, Iterable<Integer> sectionYs) {
        for (int sy : sectionYs) {
            markReading(chunk, sy);
        }
    }

    public static void unmarkReading(LevelChunk chunk, int sectionY) {
        SectionState state = SectionStage.stateOrNull(chunk, sectionY);
        if (state != null) {
            state.clearReading();
        }
    }

    public static void unmarkReadingBatch(LevelChunk chunk, Iterable<Integer> sectionYs) {
        for (int sy : sectionYs) {
            unmarkReading(chunk, sy);
        }
    }

    // ---- 调度 ----

    /**
     * 把任务调度到主线程，走该 ServerLevel 的 chunkMap 实例上的 ChunkMap.mainThreadExecutor。
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
     * <p>落档体据此决定内联执行还是 marshal：主线程上内联，因为阻塞取数期间 tick 不会跑；池线程上
     * marshal，因为档位只能由主线程写。
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
