package com.inf.farlands.client.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.client.render.AnchoredFrustum;
import com.inf.farlands.client.render.simulation.SimulationGeometry;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import com.inf.farlands.util.pos.ExactPosition;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * 客户端的模拟方块层：段与内容由服务端的包写入，提交之后由渲染与碰撞两处统一消费。
 *
 * <p>
 * 客户端不再有生产者。段的存在与否、内容、光照与群系全部来自 {@code SimulationSectionPacket}，与 {@code sections}
 * 的生命周期因此完全由服务端驱动：包来了就建，包撤了就删。
 *
 * <p>
 * 刻意不经过 {@code BlockPos}、{@code SectionPos} 与段编译。段键是三轴 long，块在段内是 0 到 15 的局部坐标，
 * 世界位置由渲染与拾取两处按相机相对量现算，不落回世界量级的 double。
 *
 * <p>
 * 线程：写入与 {@link #commit} 只在客户端主线程，包的分发点 {@code ClientPayloadDispatchMixin} 已经保证这一条；
 * {@link #snapshot} 由渲染线程与碰撞读，发布的是不可变快照，每次提交整体换一次引用。
 */
public final class Simulation {

    private final Map<SectionKey, SimulationSection> sections = new HashMap<>();

    /**
     * 内容变过、等下一次 {@link #commit} 重建 snap 的段。
     *
     * <p>
     * 只在 {@link #applySection} 的非空分支加入：段容器的写入口全树只有那一条，即 {@code set}、{@code setBiome}、
     * {@code fillBiomes}、{@code setLight}、{@code fillLight}、{@code clearAll} 都没有外部调用方，所以脏集不必
     * 给段装回调。空段分支由 {@code applyAir} 直接把 snap 置空，当场通知，不进这张表。
     */
    private final Set<SectionKey> snapDirty = new HashSet<>();

    /** 已发布的快照。列表随追加变化，实例恒定，见 {@link Snapshot}。 */
    private final Snapshot snapshot = new Snapshot();

    /** 建段用的群系容器工厂与默认群系，与关卡同一个入口设定，见 {@link #attach}。 */
    private PalettedContainerFactory biomeFactory;
    private Holder<Biome> defaultBiome;

    /** 该维度的方向明暗，与关卡同一个入口设定。 */
    private CardinalLighting cardinalLighting = CardinalLighting.DEFAULT;

    /**
     * 安装时那个客户端关卡，未安装时为 null。
     *
     * <p>
     * 碰撞注入用它把接收者限定住：{@code CollisionGetter.getBlockCollisions} 是接口 default，单机里集成
     * 服务端与 {@code PathNavigationRegion} 也会走到同一个被注入的方法，只靠客户端源集挡不住。
     */
    private ClientLevel clientLevel;

    /** 几何尚未烘的段。每 tick 按到相机的距离取最近的一批。 */
    private final Set<SectionKey> pending = new HashSet<>();

    /**
     * 关卡代际，{@link #attach} 与 {@link #clear} 时自增。在途烘焙完成时按它判结果是否还属于这一关。
     */
    private int generation;

    /** 遮挡剔除的可见集。与段表同线程，只在客户端主线程读写。 */
    private final SimulationVisibility visibility = new SimulationVisibility();

    /**
     * 影响遮挡传播的输入的版本：段集合增删、或某段掩码真的变化时自增。
     *
     * <p>
     * {@link SimulationVisibility} 按它决定要不要再提交一轮后台传播。用计数而不是布尔，是因为烘制完成的次数远多于
     * 掩码真变化的次数：同一段重烘出同一张掩码时不该重算。
     */
    private int graphVersion = 1;

    /** 客户端 tick 计数，供 {@code FLMESH} 那行读数按间隔输出。 */
    private int tickCount;

    /**
     * 临时读数，结案即删：一个间隔内的收包、段数、烘制、上传、释放与待烘堆，全在客户端主线程读写。
     *
     * <p>
     * 判两件事：{@code sectionsIn} 与 {@code packets} 的比值即每包段数；{@code pendingNow} 若单调上升，说明到达
     * 速率大于烘制速率，否则说明到达的是重复内容。
     */
    private int probePackets;

    private int probeSectionsIn;

    private int probeBuilds;

    private int probeUploads;

    private int probeReleases;

    private int probePendingPeak;

    /**
     * 每 tick 烘几段几何，取最近的那几段。
     *
     * <p>
     * 配额按「一次窗口填满要多久」定，不按单段成本。空段在 {@link #farlands$bake} 里走不到烘制，实际成本只由
     * 稠密段的条数决定，而每 tick 的稠密段数是总非空段除以目标 tick 数，与配额无关。纵向半窗 17、水平半径 16
     * 的窗口是 28,140 段，320 的配额把它压到 88 tick，即 4.4 秒；同窗口每 tick 的稠密段数是 9.1k 条，k 为
     * 每列非空段数，k=3 时约 27 条。tick 是 50 毫秒，因此单段成本的上界是 5.5 除以 k 毫秒，k=3 时为 1.8
     * 毫秒。
     *
     * <p>
     * 不与「空段不占配额」同时使用：空段现在起稀释作用，去掉之后 320 个名额会全落在稠密段上。
     */
    private static final int BUILD_PER_TICK = 320;

    /**
     * 装关卡或退世界时调。客户端不再有生产者：段与它的内容都从服务端的包来。
     *
     * <p>
     * 段的群系容器要从该 level 的工厂建，默认群系取 void，所以三件在同一处设定：不会出现关卡是新世界的而工厂
     * 是旧世界的。段容器与显存一并清空：换关卡之后旧的段没有意义，几何也不再被任何 draw 引用。
     */
    public void attach(ClientLevel level) {
        this.clientLevel = level;
        this.biomeFactory = level.palettedContainerFactory();
        this.defaultBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
        this.cardinalLighting = level.cardinalLighting();
        this.pending.clear();
        this.sections.clear();
        this.snapDirty.clear();
        this.snapshot.clear();
        this.generation++;
        this.visibility.invalidate();
        this.graphVersion++;
        SimulationMeshPool.discardCompleted();
        SimulationGeometry.instance().clear();
    }

    /**
     * 应用服务端送来的一段。
     *
     * <p>
     * 段不存在就建，已存在就整体替换；随后入待烘集合并置结构变更，由下一次 {@link #commit} 发布新快照。
     * 只在客户端主线程调，与服务端那段包的处理点同线程。
     *
     * <p>
     * 全空段保留对象但不烘焙：它的光照是邻段环境光遮蔽的输入，天光 15 与方块光 0 都存在这个段的
     * {@code DataLayer} 里，删掉对象邻段就会采到 0 光。所以走 {@code applyAir} 只装群系与光照，跳过 4096 格
     * 方块转换，不进待烘集合，并让邻段重烘一次以拿到新的光。
     */
    public void applySection(SimulationSectionKey key, LevelChunkSection container,
            int shapeMode, DataLayer blockLight, DataLayer skyLight) {
        SectionKey local = new SectionKey(key.x(), key.y(), key.z());
        if (container.hasOnlyAir()) {
            SimulationSection air = this.section(local);
            long before = air.visibility();
            air.applyAir(container, blockLight, skyLight);
            if (before != SimulationVisibility.OPEN) {
                this.graphVersion++;
            }
            this.snapshot.removeIf(each -> each.section() == air);
            SimulationGeometry.instance().drop(air);
            this.farlands$clearTintAround(local);
            this.farlands$requeueFilledAround(local);
            return;
        }
        SimulationSection section = this.section(local);
        long before = section.visibility();
        section.applyContainer(container, shapeMode, blockLight, skyLight);
        if (before != SimulationVisibility.OPEN) {
            this.graphVersion++;
        }
        this.farlands$clearTintAround(local);
        this.pending.add(local);
        this.snapDirty.add(local);
    }

    /** 丢掉服务端撤回的一段。 */
    public void dropSection(SimulationSectionKey key) {
        this.removeSection(new SectionKey(key.x(), key.y(), key.z()));
    }


    /**
     * 摘段但不碰显存：从 {@code sections} 与待烘集合摘掉，并把邻列放回待烘。
     *
     * <p>
     * 分开的理由是释放的批次：解绑成百上千列时逐列建 fence 会把主线程按在 GPU 上，所以那条路径收集键后调
     * {@link SimulationGeometry#releaseAll}。单列删除走 {@link #removeSection}，它在本方法之后补一次释放。
     */
    private void farlands$detach(SectionKey key) {
        SimulationSection removed = this.sections.remove(key);
        if (removed != null) {
            this.graphVersion++;
            this.snapshot.removeIf(each -> each.section() == removed);
            SimulationGeometry.instance().drop(removed);
        }
        this.pending.remove(key);
        this.snapDirty.remove(key);
        this.farlands$requeueNeighbors(key);
        this.farlands$clearTintAround(key);
    }

    /**
     * 清该段与横向 3x3 邻段的 tint 缓存。
     *
     * <p>
     * 群系混合取样只在本段所在的 Y 平面上做，横向最远七格，所以纵向邻段不参与，横向两侧各一段就够。段内容一换，
     * 本段与两侧邻段跨边界取样得到的块色就都旧了。
     */
    private void farlands$clearTintAround(SectionKey key) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                SimulationSection neighbor = this.sectionOrNull(key.x() + dx, key.y(), key.z() + dz);
                if (neighbor != null) {
                    neighbor.clearTints();
                }
            }
        }
    }

    /**
     * 邻段消失时的全量重烘。
     *
     * <p>
     * 一段被摘掉，它周围每一段的几何都可能变：面剔除只跨一格，环境光遮蔽的角采样与群系混合还会落到对角邻段。
     * 摘除让邻段从「有」变「无」，没有掩码可用，所以这一支把 26 个邻位一律放回待烘。
     */
    private void farlands$requeueNeighbors(SectionKey key) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    SectionKey neighbor = new SectionKey(key.x() + dx, key.y() + dy, key.z() + dz);
                    if (this.sections.containsKey(neighbor)) {
                        this.pending.add(neighbor);
                    }
                }
            }
        }
    }

    /**
     * 邻段到达时的定向重烘：只放回「烘制那一版几何时确实缺了这一格」的邻段。
     *
     * <p>
     * 判据落在对方的 {@link SimulationSection#missing()} 上。邻段 M 相对自己看，本段位于 {@code M + (-d)}，所以查
     * 的位是 {@code (-dx + 1) * 9 + (-dy + 1) * 3 + (-dz + 1)}，与快照里的位序同一套。
     *
     * <p>
     * 全量的那一支换来的是正确性，这一支换来的是次数：一段若是等邻域齐了才烘的，它的掩码里没有这一位，后到的邻段
     * 就不会替它排一次队。实测的每段烘制次数由此从 4.588 次往下走。
     */
    private void farlands$requeueFilledAround(SectionKey key) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    SectionKey neighborKey = new SectionKey(key.x() + dx, key.y() + dy, key.z() + dz);
                    SimulationSection neighbor = this.sections.get(neighborKey);
                    if (neighbor == null) {
                        continue;
                    }
                    int bit = (-dx + 1) * 9 + (-dy + 1) * 3 + (-dz + 1);
                    if ((neighbor.missing() & (1 << bit)) != 0) {
                        this.pending.add(neighborKey);
                    }
                }
            }
        }
    }

    /**
     * 每客户端 tick 一次：取走完成结果、提交新一批、发布快照。
     *
     * <p>
     * 绑定与解绑不在这里：段集合完全由服务端的包驱动。烘制在工作线程上进行，这里只剩快照、提交与上传三件事，
     * 上传占的是显存侧那点时间，不再是整段遮蔽计算。
     */
    public void tickSimulation() {
        this.tickCount++;
        SimulationTimings.tick();
        long tickStart = SimulationTimings.begin();
        this.farlands$drainCompleted();
        long submitStart = SimulationTimings.begin();
        net.minecraft.world.entity.player.Player player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            this.farlands$submit(0L, 0L, false);
        } else {
            ExactPosition exact = (ExactPosition) player;
            this.farlands$submit(exact.farlands$cellCoord(ExactPosition.POS_X) >> 4,
                    exact.farlands$cellCoord(ExactPosition.POS_Z) >> 4, true);
        }
        SimulationTimings.end(SimulationTimings.SUBMIT, submitStart);
        long commitStart = SimulationTimings.begin();
        this.commit();
        SimulationTimings.end(SimulationTimings.COMMIT, commitStart);
        SimulationTimings.pending(this.pending.size());
        SimulationTimings.end(SimulationTimings.TICK, tickStart);
        this.farlands$reportProbe();
    }

    /**
     * 临时读数，结案即删：记一次收包与它带的段数。
     *
     * <p>
     * 由包处理入口调，与 {@link #applySection} 同线程。
     */
    public void notePacket(int sections) {
        this.probePackets++;
        this.probeSectionsIn += sections;
    }

    /** 临时读数，结案即删：每 200 tick 打一行，随后清零。 */
    private void farlands$reportProbe() {
        this.probePendingPeak = Math.max(this.probePendingPeak, this.pending.size());
        if (this.tickCount % 200 != 0) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "FLMESH packets={} sectionsIn={} builds={} uploads={} releases={} pendingNow={} pendingPeak={}",
                this.probePackets, this.probeSectionsIn, this.probeBuilds, this.probeUploads, this.probeReleases,
                this.pending.size(), this.probePendingPeak);
        this.probePackets = 0;
        this.probeSectionsIn = 0;
        this.probeBuilds = 0;
        this.probeUploads = 0;
        this.probeReleases = 0;
        this.probePendingPeak = 0;
    }

    /**
     * 取走完成结果：校验代际与版本，通过则写掩码并上传，否则丢弃。
     *
     * <p>
     * 代际不符说明关卡换过，结果没有落点。版本不符说明烘制期间这一段的内容又变了，把键放回待烘等下一轮拿新内容。
     * 段已不在表里说明它被卸载了，几何由卸载那条路径释放过。
     *
     * <p>
     * 掩码在网格之外单独写回：实心 Section 不产四边形，但它的掩码是最强的遮挡输入，不能跟着网格一起丢。
     */
    private void farlands$drainCompleted() {
        long drainStart = SimulationTimings.begin();
        SimulationMeshPool.Completed done;
        while ((done = SimulationMeshPool.take()) != null) {
            BakeSnapshot snapshot = done.snapshot();
            SectionKey key = snapshot.key();
            SimulationMesher.Build build = done.build();
            if (snapshot.generation() != this.generation) {
                SimulationMesher.release(build);
                continue;
            }
            SimulationSection section = this.sections.get(key);
            if (section == null) {
                SimulationMesher.release(build);
                continue;
            }
            if (section.revision() != snapshot.revision()) {
                // 内容在烘制期间变过：结果作废，tint 缓存也可能被那次烘焙写进了旧值，一并清掉再排回待烘。
                SimulationMesher.release(build);
                section.clearTints();
                this.pending.add(key);
                continue;
            }
            if (build == null) {
                // 烘制抛了异常：几何作废，掩码退回全开，免得一个残缺的遮挡结论留在可见集里。
                SimulationGeometry.instance().drop(section);
                if (section.visibility() != SimulationVisibility.OPEN) {
                    section.setVisibility(SimulationVisibility.OPEN);
                    this.graphVersion++;
                }
                this.probeReleases++;
                continue;
            }
            this.probeBuilds++;
            section.setMissing(snapshot.missing());
            long mask = build.visibility();
            if (section.visibility() != mask) {
                section.setVisibility(mask);
                this.graphVersion++;
            }
            if (build.mesh() == null) {
                SimulationGeometry.instance().drop(section);
                this.probeReleases++;
            } else {
                SimulationGeometry.instance().stage(section, build.mesh());
                this.probeUploads++;
            }
        }
        SimulationTimings.end(SimulationTimings.DRAIN, drainStart);
    }

    /**
     * 从待烘集里挑一批提交到烘焙池。
     *
     * <p>
     * 候选只遍历待烘集，可见性用 {@link SimulationVisibility#containsVisible} 现判：待烘集峰值六千量级，而可见集
     * 是 112,315 段，反着遍历等于每 tick 扫一遍全窗。
     *
     * <p>
     * 选谁烘的判据仍是「会被画」：看不见的段即使烘出来也只会占着显存与带宽。
     *
     * <p>
     * 可见判定来自上一次遮挡传播，有一帧滞后；一帧之内新进的段最多晚一帧加一次烘制出现。
     *
     * <p>
     * 每 tick 提交量受两个上限夹住：一段一批的配额，与烘焙池的空余名额。后者存在的理由不是省内存，而是防积压，
     * 一段的内容在途期间可能又变了。
     *
     * <p>
     * 无玩家基准时不排序，按待烘集的迭代顺序取：段号在模拟之地是两亿级，拿 {@code (0, 0)} 当中心会把远处的列排到
     * 前面。
     */
    private void farlands$submit(long centerX, long centerZ, boolean positioned) {
        int quota = Math.min(BUILD_PER_TICK, SimulationMeshPool.freeSlots());
        if (quota <= 0 || this.pending.isEmpty()) {
            return;
        }
        SectionKey[] picked = new SectionKey[quota];
        int found = positioned
                ? this.farlands$pickNearestPending(picked, centerX, centerZ)
                : this.farlands$takeFirstPending(picked);
        for (int i = 0; i < found; i++) {
            SectionKey key = picked[i];
            this.pending.remove(key);
            SimulationSection section = this.sections.get(key);
            if (section == null) {
                continue;
            }
            if (section.count() == 0) {
                SimulationGeometry.instance().drop(section);
                continue;
            }
            SimulationMeshPool.submit(this.farlands$snapshot(key, section));
        }
    }

    /**
     * 把待烘集里既可见又最近的若干个填进 {@code picked}，返回实际填了几个。
     *
     * <p>
     * 选取用容量等于本批配额的固定最大堆，堆顶是已选中最远的一个。候选只与堆顶比一次，近了才替换并下沉。
     */
    private int farlands$pickNearestPending(SectionKey[] picked, long centerX, long centerZ) {
        int want = picked.length;
        long[] distances = new long[want];
        int size = 0;
        for (SectionKey key : this.pending) {
            if (!this.visibility.containsVisible(key.x(), key.y(), key.z())) {
                continue;
            }
            long dx = key.x() - centerX;
            long dz = key.z() - centerZ;
            long distance = dx * dx + dz * dz;
            if (size < want) {
                picked[size] = key;
                distances[size] = distance;
                farlands$siftUp(picked, distances, size);
                size++;
                continue;
            }
            if (distance < distances[0]) {
                picked[0] = key;
                distances[0] = distance;
                farlands$siftDown(picked, distances, size);
            }
        }
        return size;
    }

    /** 没有玩家基准时按待烘集的迭代顺序取，不做距离排序。 */
    private int farlands$takeFirstPending(SectionKey[] picked) {
        int want = picked.length;
        int found = 0;
        for (SectionKey key : this.pending) {
            if (found == want) {
                break;
            }
            if (this.visibility.containsVisible(key.x(), key.y(), key.z())) {
                picked[found++] = key;
            }
        }
        return found;
    }

    /** 新入堆的元素上浮到它在最大堆里的位置。 */
    private static void farlands$siftUp(SectionKey[] keys, long[] distances, int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (distances[parent] >= distances[index]) {
                return;
            }
            farlands$swap(keys, distances, parent, index);
            index = parent;
        }
    }

    /** 替换堆顶之后下沉到它在最大堆里的位置。 */
    private static void farlands$siftDown(SectionKey[] keys, long[] distances, int size) {
        int index = 0;
        while (true) {
            int left = (index << 1) + 1;
            if (left >= size) {
                return;
            }
            int right = left + 1;
            int largest = right < size && distances[right] > distances[left] ? right : left;
            if (distances[index] >= distances[largest]) {
                return;
            }
            farlands$swap(keys, distances, index, largest);
            index = largest;
        }
    }

    private static void farlands$swap(SectionKey[] keys, long[] distances, int a, int b) {
        SectionKey key = keys[a];
        keys[a] = keys[b];
        keys[b] = key;
        long distance = distances[a];
        distances[a] = distances[b];
        distances[b] = distance;
    }

    /**
     * 取一份烘焙快照。
     *
     * <p>
     * 27 格邻域在这里解析：工作线程不能查 {@code sections}，那是普通 HashMap。客户端那几样物件也在这里取，
     * 资源重载与设置界面都会换掉它们。
     */
    private BakeSnapshot farlands$snapshot(SectionKey key, SimulationSection section) {
        SimulationSection[] neighbors = new SimulationSection[27];
        int missing = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int index = (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
                    SimulationSection neighbor = this.sectionOrNull(key.x() + dx, key.y() + dy, key.z() + dz);
                    neighbors[index] = neighbor;
                    if (neighbor == null) {
                        missing |= 1 << index;
                    }
                }
            }
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        return new BakeSnapshot(key, this.generation, section.revision(), section, section.occupiedSnapshot(),
                section.occupiedCount(), neighbors, missing, this.defaultBiome, minecraft.getBlockColors(),
                minecraft.getModelManager().getBlockStateModelSet(), this.cardinalLighting,
                minecraft.options.ambientOcclusion().get(), minecraft.options.biomeBlendRadius().get());
    }

    /** 取或建一段。键已在手上时用这一支，省一次键构造。 */
    public SimulationSection section(SectionKey key) {
        SimulationSection section = this.sections.get(key);
        if (section == null) {
            section = new SimulationSection(key.x(), key.y(), key.z(), this.biomeFactory, this.defaultBiome);
            this.sections.put(key, section);
            this.graphVersion++;
        }
        return section;
    }

    /**
     * 取或建一段。可玩界内的段也会返回对象，只是写入被丢弃。
     *
     * <p>
     * 建出的段必须由调用方用 {@link #removeSection} 删除。渲染、碰撞、拾取都按 {@code sections} 判存在，所以建了
     * 不删会一直参与三路取数，直到 {@link #clear} 或换层。
     */
    public SimulationSection section(long sectionX, long sectionY, long sectionZ) {
        return this.section(new SectionKey(sectionX, sectionY, sectionZ));
    }

    /**
     * 摘掉一段。键已在手上时用这一支。
     *
     * <p>
     * 段的生命周期归调用方：谁建谁删，本类不做任何按时间或按距离的回收。本方法收口四件：从 {@code sections}
     * 摘、从待烘集合摘、释放显存、把邻列放回待烘。必须在客户端主线程调，显存释放走 fence 延迟关闭。
     */
    public void removeSection(SectionKey key) {
        this.farlands$detach(key);
    }

    /** 摘掉一段。 */
    public void removeSection(long sectionX, long sectionY, long sectionZ) {
        this.removeSection(new SectionKey(sectionX, sectionY, sectionZ));
    }

    /** 全部清空。卸关卡时调用。 */
    public void clear() {
        this.clientLevel = null;
        this.pending.clear();
        this.sections.clear();
        this.snapDirty.clear();
        this.snapshot.clear();
        this.generation++;
        this.visibility.invalidate();
        this.graphVersion++;
        SimulationMeshPool.discardCompleted();
    }

    /**
     * 本帧要画的 Section 列表：交给遮挡剔除层，由它按需提交后台传播、按需重裁，返回裁剪集。
     *
     * <p>
     * 入参是相机帧的锚点与局部量，Section 号与段内偏移都由这里现算：调用方在渲染包里，算完还得原样传回来，
     * 不如只给帧表示。
     *
     * <p>
     * 传播跑在后台线程，由「图输入版本变化」或「相机段变化」驱动，所以这里不再有节流：异步本身就是节流，一次只有
     * 一个任务在途。裁剪在主线程做，只在图发布或相机转动跨 2 度时重跑。
     */
    public List<SnapSection> visibleSections(long cameraAnchorX, long cameraAnchorY, long cameraAnchorZ,
            double cameraLocalX, double cameraLocalY, double cameraLocalZ, float xRot, float yRot,
            AnchoredFrustum frustum) {
        long blockX = cameraAnchorX + (long) Math.floor(cameraLocalX);
        long blockY = cameraAnchorY + (long) Math.floor(cameraLocalY);
        long blockZ = cameraAnchorZ + (long) Math.floor(cameraLocalZ);
        long sectionX = blockX >> 4;
        long sectionY = blockY >> 4;
        long sectionZ = blockZ >> 4;
        double inSectionX = cameraLocalX - Math.floor(cameraLocalX) + (double) (blockX - (sectionX << 4));
        double inSectionY = cameraLocalY - Math.floor(cameraLocalY) + (double) (blockY - (sectionY << 4));
        double inSectionZ = cameraLocalZ - Math.floor(cameraLocalZ) + (double) (blockZ - (sectionZ << 4));
        return this.visibility.update(this, new SimulationVisibility.View(sectionX, sectionY, sectionZ, inSectionX,
                inSectionY, inSectionZ, cameraAnchorX, cameraAnchorY, cameraAnchorZ, cameraLocalX, cameraLocalY,
                cameraLocalZ, xRot, yRot, frustum));
    }

    /** 图输入的版本，见 {@link #graphVersion}。 */
    int graphVersion() {
        return this.graphVersion;
    }

    /** 裁剪集内容的版本，供渲染批次缓存判重。 */
    public int visibleVersion() {
        return this.visibility.culledVersion();
    }

    /** 段表本体，供 {@link SimulationVisibility} 传播用。同包可见，只在客户端主线程读。 */
    Map<SectionKey, SimulationSection> sectionMap() {
        return this.sections;
    }

    public int sectionCount() {
        return this.sections.size();
    }

    /** 当前快照，任意线程可读。 */
    public Snapshot snapshot() {
        return this.snapshot;
    }

    /** 该 level 的默认群系，缺邻段时群系混合回落到它。 */
    public Holder<Biome> defaultBiome() {
        return this.defaultBiome;
    }

    /** 该维度的方向明暗，着色时按面取。 */
    public CardinalLighting cardinalLighting() {
        return this.cardinalLighting;
    }

    /** 安装时那个客户端关卡，未安装时为 null。碰撞注入据此限定接收者。 */
    public ClientLevel level() {
        return this.clientLevel;
    }

    /** 只查不建。该列没有模拟段时返回 null。 */
    public SimulationSection sectionOrNull(long sectionX, long sectionY, long sectionZ) {
        return this.sections.get(new SectionKey(sectionX, sectionY, sectionZ));
    }

    /**
     * 把全部段放回待烘集合。
     *
     * <p>
     * 资源重载与已烘入顶点的全局选项变化后调，配合清空显存与模型缓存重建。不重跑生产者：段还是那些，只是几何
     * 要重新烘。按 {@code sections} 取，所以手动建的段一并重烘。
     */
    public void requeueAllSections() {
        this.pending.addAll(this.sections.keySet());
    }

    /**
     * 把脏段重建并发布新快照，主线程每 tick 一次。
     *
     * <p>
     * 只在有段变脏或段集合有增删时换快照，其余时候一次也不发布。
     */
    public void commit() {
        if (this.snapDirty.isEmpty()) {
            return;
        }
        int reach = Math.max(2, net.minecraft.client.Minecraft.getInstance().options.biomeBlendRadius().get());
        for (SectionKey key : this.snapDirty) {
            SimulationSection section = this.sections.get(key);
            if (section == null || !section.eligible()) {
                continue;
            }
            SimulationSection.Dirty dirty = section.takeDirty();
            if (dirty == null) {
                continue;
            }
            boolean had = section.snap() != null;
            section.rebuild();
            SnapSection rebuilt = section.snap();
            if (had != (rebuilt != null)) {
                // 只有碰撞用的快照在这里增减；渲染用的可见集由后台传播整体产出，见 SimulationVisibility。
                if (rebuilt == null) {
                    this.snapshot.removeIf(each -> each.section() == section);
                } else {
                    this.snapshot.add(rebuilt);
                }
            }
            this.farlands$requeueGeometry(section, dirty, reach);
        }
        this.snapDirty.clear();
    }

    /**
     * 把变脏的段与它可能影响到的邻段放回待烘集合。
     *
     * <p>
     * 邻段的判据是采样半径：群系混合最远取 {@code biomeBlendRadius} 格，平滑光的角采样与半透明探测最远取
     * 2 格，所以 {@code reach} 取两者的大者。三轴各自判「变更范围是否贴到段边界」，贴到才带上该方向的邻段，
     * 段内部写入只重烘本段。
     *
     * <p>
     * 邻段还要再过一道掩码：只有当它烘制当前这一版几何时本段还缺席，才值得重烘。本段自己不受这一道约束，它总
     * 要重烘。这一道把「每到达一段就替 26 个邻段各排一次队」压成「只替当时确实缺了它的那些段排队」。
     *
     * <p>
     * 只放回存在的段：未建的段放回去也只会在烘制时被跳过。
     */
    private void farlands$requeueGeometry(SimulationSection section, SimulationSection.Dirty dirty, int reach) {
        long requeueStart = SimulationTimings.begin();
        long centerX = section.sectionX();
        long centerY = section.sectionY();
        long centerZ = section.sectionZ();
        boolean west = dirty.minX() < reach;
        boolean east = dirty.maxX() > SimulationSection.SIZE - 1 - reach;
        boolean down = dirty.minY() < reach;
        boolean up = dirty.maxY() > SimulationSection.SIZE - 1 - reach;
        boolean north = dirty.minZ() < reach;
        boolean south = dirty.maxZ() > SimulationSection.SIZE - 1 - reach;
        for (int dx = -1; dx <= 1; dx++) {
            if (dx < 0 && !west || dx > 0 && !east) {
                continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
                if (dy < 0 && !down || dy > 0 && !up) {
                    continue;
                }
                for (int dz = -1; dz <= 1; dz++) {
                    if (dz < 0 && !north || dz > 0 && !south) {
                        continue;
                    }
                    SectionKey key = new SectionKey(centerX + dx, centerY + dy, centerZ + dz);
                    SimulationSection neighbor = this.sections.get(key);
                    if (neighbor == null) {
                        continue;
                    }
                    if (dx != 0 || dy != 0 || dz != 0) {
                        int bit = (-dx + 1) * 9 + (-dy + 1) * 3 + (-dz + 1);
                        if ((neighbor.missing() & (1 << bit)) == 0) {
                            continue;
                        }
                    }
                    this.pending.add(key);
                }
            }
        }
        SimulationTimings.end(SimulationTimings.REQUEUE, requeueStart);
    }

    /**
     * 段键与列键：三轴 long。列键就是该列那一段的键，所以绑定的列与段用同一个身份。
     *
     * <p>
     * 公开是因为 {@code SimulationGeometry} 与 {@code SimulationRenderPass} 在另一个包里要用它做显存表的键。
     */
    public record SectionKey(long x, long y, long z) {
    }

    /**
     * 已发布的快照。
     *
     * <p>
     * 列表随追加变化，实例恒定。全树唯一的读点是碰撞注入里的 {@code sections().isEmpty()}，它只判空不遍历，所以
     * 不必每 tick 复制一份；段增删与 snap 转变在各自的入口当场追加或摘除。
     */
    public static final class Snapshot {

        private final List<SnapSection> sections = new ArrayList<>();

        private final List<SnapSection> published = Collections.unmodifiableList(this.sections);

        Snapshot() {
        }

        public List<SnapSection> sections() {
            return this.published;
        }

        void add(SnapSection snap) {
            this.sections.add(snap);
        }

        void removeIf(Predicate<SnapSection> test) {
            this.sections.removeIf(test);
        }

        void clear() {
            this.sections.clear();
        }
    }

    /**
     * 快照里的一段：段坐标系的三轴、它的键，以及指向段本身的引用。
     *
     * <p>
     * 引用而不是逐块拷贝：渲染读的是上传好的段几何，碰撞与拾取按 {@code occupiedIndex} 现取。代价是快照从
     * 完全不可变退成「引用不可变」，那两张容器只在客户端主线程写，碰撞与拾取也在主线程调，所以读侧不跨线程。
     */
    public record SnapSection(long sectionX, long sectionY, long sectionZ, SectionKey key,
            SimulationSection section) {

        /** 世界坐标的段原点。 */
        public long originX() {
            return this.sectionX * SimulationSection.SIZE;
        }

        public long originY() {
            return this.sectionY * SimulationSection.SIZE;
        }

        public long originZ() {
            return this.sectionZ * SimulationSection.SIZE;
        }
    }
}
