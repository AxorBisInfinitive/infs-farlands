package com.inf.farlands.simulation;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.simulation.int64.terrainFiller.SimulationFiller;
import com.inf.farlands.simulation.int64.terrainFiller.StandardSimulationFiller;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import com.inf.farlands.simulation.model.SimulationSectionStage;
import com.inf.farlands.simulation.model.SimulationStore;
import com.inf.farlands.simulation.serialize.SimulationIO;
import com.inf.farlands.simulation.serialize.SimulationSerializer;
import com.inf.farlands.simulation.serialize.SimulationStorage;
import com.inf.farlands.simulation.terrain.SimulationPool;
import com.inf.farlands.simulation.terrain.SimulationTask;

/**
 * 模拟段的生命周期编排：读回优先、生产入池、释放落盘、周期刷盘、关服收尾。
 *
 * <p>
 * 与地形侧的 {@code SectionLifecycle} 是同一类职责，但只有三档阶段，所以没有候选表也没有准入：谁该开工由
 * {@link SimulationServer} 的视距并集决定，本类只在段进入与离开并集时被调。
 *
 * <p>
 * 两条门。下发门在 {@code SimulationServer.send}，判据是 {@link SimulationSectionStage#isTerminal}；落盘门
 * 在本类的 {@link #persistSection}，同一条判据。低于终态的段既不发也不写，它是正在被池线程改的那一份。
 *
 * <p>
 * 全部在主线程调用，唯一的例外是生产任务体与 IO 线程，它们都不碰段表。生产任务的收尾经
 * {@link SimulationPool} 投回主线程，那一步才动段表与档位。
 */
public final class SimulationLifecycle {

    /**
     * 唯一的填充器。逻辑全在 {@code AbstractSimulationFiller}，实例无状态。
     *
     * <p>系统不在这里选：填充器按 {@code SimulationSystem.SELECTED} 现取，换系统只改枚举那一处。
     */
    private static final SimulationFiller FILLER = new StandardSimulationFiller();

    /** 读回在途的段。键含维度：同一个段号在两个维度里互不相干。 */
    private record PendingKey(ResourceKey<Level> dimension, SimulationSectionKey key) {
    }

    private static final Set<PendingKey> LOADING = new HashSet<>();

    private SimulationLifecycle() {
    }

    /**
     * 该段进入视距：盘上有就读回来，没有就建空壳并入池生产。已有则只做一件事，撤销可能在途的释放标记。
     *
     * <p>
     * 撤销那一步不能省：段在落盘途中又回到视距时，写提交回调会在收尾时把它摘掉，而玩家其实还在看它。
     */
    public static void ensureSection(ServerLevel level, SimulationSectionKey key) {
        ResourceKey<Level> dimension = level.dimension();
        if (SimulationStore.contains(dimension, key)) {
            SimulationStore.unmarkReleasing(dimension, key);
            return;
        }
        PendingKey pending = new PendingKey(dimension, key);
        if (!LOADING.add(pending)) {
            return; // 上一次读回还没回来，等它的回调
        }
        SimulationStorage.SlotRef ref;
        try {
            ref = SimulationIO.slotAt(level, key.x(), key.y(), key.z());
        } catch (RuntimeException e) {
            InfsFarlands.LOGGER.error("farlands: ssa slot lookup failed section={},{},{}", key.x(), key.y(),
                    key.z(), e);
            LOADING.remove(pending);
            produce(level, key);
            return;
        }
        if (ref == null) {
            LOADING.remove(pending);
            produce(level, key);
            return;
        }
        Path path = SimulationIO.findPath(level, key.x(), key.y(), key.z());
        if (path == null) {
            // 槽位查得到就说明映射里有这个区域，这里落空只可能是表在两次调用之间被换掉。
            LOADING.remove(pending);
            produce(level, key);
            return;
        }
        List<SimulationIO.SlotKey> keys = List
                .of(new SimulationIO.SlotKey(key.x(), key.y(), key.z(), ref));
        SimulationIO.submitRead(level, path, keys, level.palettedContainerFactory(),
                decoded -> finishRead(level, key, pending, decoded));
    }

    /**
     * 该段离开视距：终态的落盘，非终态的直接丢。
     *
     * <p>
     * 落盘走两段：这里只置标记并编码，写提交回调在主线程做 commit 与摘条目。标记期间同 key 的段仍在表里，
     * 所以玩家在写完成前又走回来时命中的还是同一个对象。
     */
    public static void release(ServerLevel level, SimulationSectionKey key) {
        ResourceKey<Level> dimension = level.dimension();
        SimulationSectionData data = SimulationStore.get(dimension, key);
        if (data == null || data.isReleasing()) {
            return;
        }
        if (!SimulationSectionStage.isTerminal(data.stage())) {
            // 没写完的段没有落盘价值：盘上要么没有它，要么是更早的一份。
            SimulationStore.removeIfSame(dimension, key, data);
            return;
        }
        data.markReleasing();
        persistSection(level, key, data);
    }

    /**
     * 把一段编码并提交写盘。返回是否真的提交了。
     *
     * <p>
     * 编码在主线程做。这样做是为了免掉一把与下发争同一容器的锁：下发侧的 {@code LevelChunkSection.write} 与
     * 这边的 codec 打包都会取容器自己的线程检测器，同线程串行就没有这个问题。代价是一整条带释放时编码都在
     * 主线程上，见类注释的未结项。
     */
    public static boolean persistSection(ServerLevel level, SimulationSectionKey key, SimulationSectionData data) {
        ResourceKey<Level> dimension = level.dimension();
        Path path = SimulationIO.pathForWrite(level, key.x(), key.y(), key.z());
        PalettedContainerFactory factory = level.palettedContainerFactory();
        byte[] entry;
        try {
            entry = SimulationSerializer.encode(data.section, data.blockLight, data.skyLight, data.shapeMode(),
                    data.stage(), factory, key.x(), key.y(), key.z());
        } catch (RuntimeException e) {
            InfsFarlands.LOGGER.error("farlands: ssa encode failed section={},{},{}", key.x(), key.y(), key.z(), e);
            data.clearReleasing();
            return false;
        }
        SimulationStorage storage = SimulationIO.getOrOpen(path);
        SimulationStorage.PendingWrite pw = storage
                .prepareWrite(SimulationIO.slotIndex(key.x(), key.y(), key.z()), entry);
        if (pw == null) {
            data.clearReleasing();
            return false;
        }
        SimulationIO.submitWrite(level, path, List.of(pw), () -> {
            SimulationIO.getOrOpen(path).commitWrite(pw);
            data.clearPersistDirty();
            // 提交期间又进了视距的话，标记已被 ensureSection 撤销，条目要留给玩家。
            if (data.isReleasing()) {
                SimulationStore.removeIfSame(dimension, key, data);
            }
        });
        return true;
    }

    /** 周期刷盘：全部终态且待落盘的段各提交一次，随后刷偏移表。 */
    public static void flushAllDirty(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
                SimulationSectionData data = SimulationStore.get(dimension, key);
                if (data == null || data.isReleasing() || !data.isPersistDirty()) {
                    continue;
                }
                if (!SimulationSectionStage.isTerminal(data.stage())) {
                    continue;
                }
                persistSection(level, key, data);
            }
        }
        SimulationIO.flushAllOffsetTables();
    }

    /**
     * 关服：同步刷掉全部待落盘的段，再排空 IO、刷偏移表、关句柄。
     *
     * <p>
     * 与地形侧的 {@code SectionLifecycle.shutdownSyncFlush} 同形，只是范围更小：没有 chunk 遍历，直接走段表。
     * 同步写不依赖异步回调，所以 commit 也就地做。
     */
    public static void shutdownSyncFlush(MinecraftServer server) {
        SimulationIO.drainMainThreadTasks(server);
        for (ServerLevel level : server.getAllLevels()) {
            PalettedContainerFactory factory = level.palettedContainerFactory();
            ResourceKey<Level> dimension = level.dimension();
            for (SimulationSectionKey key : SimulationStore.keys(dimension)) {
                SimulationSectionData data = SimulationStore.get(dimension, key);
                if (data == null || !data.isPersistDirty()) {
                    continue;
                }
                if (!SimulationSectionStage.isTerminal(data.stage())) {
                    continue;
                }
                Path path = SimulationIO.pathForWrite(level, key.x(), key.y(), key.z());
                byte[] entry;
                try {
                    entry = SimulationSerializer.encode(data.section, data.blockLight, data.skyLight,
                            data.shapeMode(), data.stage(), factory, key.x(), key.y(), key.z());
                } catch (RuntimeException e) {
                    InfsFarlands.LOGGER.error("farlands: ssa shutdown encode failed section={},{},{}", key.x(),
                            key.y(), key.z(), e);
                    continue;
                }
                SimulationStorage storage = SimulationIO.getOrOpen(path);
                SimulationStorage.PendingWrite pw = storage
                        .prepareWrite(SimulationIO.slotIndex(key.x(), key.y(), key.z()), entry);
                if (pw == null) {
                    continue;
                }
                try {
                    storage.doWrite(List.of(pw));
                } catch (Exception e) {
                    InfsFarlands.LOGGER.error("farlands: ssa shutdown write failed {}", path, e);
                    continue;
                }
                storage.commitWrite(pw);
                data.clearPersistDirty();
            }
        }
        SimulationIO.flushAllSync();
        SimulationIO.awaitIODrain();
        SimulationIO.clearCache();
    }

    /** 读回回调，主线程。盘上的段没到终态就不采用，按未命中处理，重跑生产者。 */
    private static void finishRead(ServerLevel level, SimulationSectionKey key, PendingKey pending,
            List<SimulationIO.DecodedEntry> decoded) {
        LOADING.remove(pending);
        ResourceKey<Level> dimension = level.dimension();
        if (SimulationStore.contains(dimension, key)) {
            return;
        }
        for (SimulationIO.DecodedEntry entry : decoded) {
            if (!SimulationSectionStage.isTerminal(entry.decoded().stage())) {
                continue;
            }
            SimulationStore.putDecoded(dimension, key, entry.decoded().section(), entry.decoded().shapeMode(),
                    entry.decoded().blockLight(), entry.decoded().skyLight(), entry.decoded().stage());
            return;
        }
        produce(level, key);
    }

    /**
     * 建空壳、标 {@code NOISE}、把生产交给池。
     *
     * <p>
     * 提交被拒时要摘掉这一格：池只在关闭时拒绝，而那一格之后再没有别的出口会碰它，留着就是一个永久的死档。
     * 摘掉之后，下一次 {@code ensureSection} 的 {@code contains} 变假，会重新建壳入池，因此是自愈的。
     */
    private static void produce(ServerLevel level, SimulationSectionKey key) {
        SimulationSectionData data = SimulationStore.createEmpty(level, key);
        data.advanceTo(SimulationSectionStage.NOISE);
        boolean submitted = SimulationPool.submitProduction(level,
                new SimulationTask(level, key, data, FILLER),
                success -> finishProduction(level, key, data, success));
        if (!submitted) {
            SimulationStore.removeIfSame(level.dimension(), key, data);
        }
    }

    /**
     * 生产收尾，主线程。失败不升档，段停在 {@code NOISE}，等它离开视距时由 {@link #release} 丢弃。
     *
     * <p>
     * 顺序是契约：判身份、写光、升档、置两个脏位。判身份在前，否则会往已经被替换掉的旧对象上白写一份；写光
     * 在升档之前，因为唯一读光的两处都以终态为前置，反过来就会漏出「已终态而光是空的」的窗口。
     */
    private static void finishProduction(ServerLevel level, SimulationSectionKey key, SimulationSectionData data,
            boolean success) {
        if (!success) {
            return;
        }
        if (SimulationStore.get(level.dimension(), key) != data) {
            return;
        }
        applyLight(data);
        data.advanceTo(SimulationSectionStage.LIGHTED);
        data.markPersistDirty();
        data.markDirty();
    }

    /**
     * 生产结束后的统一光照：天光 15、方块光 0。
     *
     * <p>
     * 两级都走 {@code DataLayer.fill}，它只置默认值、不分配数组，所以这一步是两次字段写。填 0 的方块光保持
     * 空层，落盘被省略、线上占一字节；填 15 的天光保持同质层，线上同样是一字节。
     *
     * <p>
     * 不碰段容器、不碰光照引擎：模拟段的光只有这两个层承载，下发读它们、客户端装它们。
     */
    private static void applyLight(SimulationSectionData data) {
        data.skyLight.fill(15);
        data.blockLight.fill(0);
    }

    /** 关服时清掉读回在途集合与映射表缓存。段表由 {@code SimulationStore.clearAll} 清。 */
    public static void clearWorldState() {
        LOADING.clear();
        com.inf.farlands.simulation.serialize.SimulationFileIndex.clearWorldState();
    }
}
