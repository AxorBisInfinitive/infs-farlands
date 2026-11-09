package com.inf.farlands.client.simulation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /** 内容变过、等下一次 {@link #commit} 登记进渲染列表的段。 */
    private final Set<SectionKey> snapDirty = new HashSet<>();

    /** 27 格邻域缓存，按段键存。只有段集合增删时才整份重建，见 {@link Neighborhood}。 */
    private final Map<SectionKey, Neighborhood> neighborhoods = new HashMap<>();

    /** 段集合的增删代数。邻域缓存的段引用按它判是否还算数。 */
    private int structureVersion;

    private volatile Snapshot snapshot = new Snapshot();

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
        this.neighborhoods.clear();
        this.structureVersion++;

        this.snapshot = new Snapshot();
        this.generation++;
        SimulationMeshPool.discardCompleted();
        SimulationGeometry.instance().clear();
    }

    /**
     * 应用服务端送来的一段。
     *
     * <p>
     * 段不存在就建；已有段先与来包逐格比较，内容相同则整条链跳过，不同才替换、标脏、入待烘。比较那一趟同时算出
     * 差异盒，供 {@link #commit} 只让贴着真差异的邻段失效。只在客户端主线程调，与服务端那段包的处理点同线程。
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
            SimulationSection section = this.section(local);
            SnapSection stale = section.snap();
            section.applyAir(container, blockLight, skyLight);
            if (stale != null) {
                this.snapshot.sectionsMutable().remove(stale);
            }
            SimulationGeometry.instance().release(local);
            this.farlands$clearTintAround(local);
            this.farlands$requeueNeighbors(local);
            return;
        }
        SimulationSection section = this.section(local);
        // 内容与现段逐格相同就什么都不做：不换容器、不标脏、不入待烘，因此也不触发邻段扩散。
        if (!section.applyIfChanged(container, shapeMode, blockLight, skyLight)) {
            return;
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
            this.structureVersion++;
            this.neighborhoods.remove(key);
            SnapSection stale = removed.snap();
            if (stale != null) {
                this.snapshot.sectionsMutable().remove(stale);
                removed.clearSnap();
            }
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
     * 邻段重烘：邻段的存在性也是烘制输入。
     *
     * <p>
     * 剔除跨段查邻块，光与群系也在段边界外采样，所以一段的绑定或解绑会让邻段的烘制结果变旧。数据变更走
     * {@code farlands$requeueGeometry} 的扩散，这条只补绑定与解绑；不补的话，邻段解绑后边界那一面会缺。
     *
     * <p>
     * 只放回存在的六向邻段，对角不需要：剔除与角采样只沿面方向。存在性变化没有脏范围可判，六个面邻段一律带上。
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
     * 每客户端 tick 一次：取走完成结果、提交新一批、发布快照。
     *
     * <p>
     * 绑定与解绑不在这里：段集合完全由服务端的包驱动。烘制在工作线程上进行，这里只剩快照、提交与上传三件事，
     * 上传占的是显存侧那点时间，不再是整段遮蔽计算。
     */
    public void tickSimulation() {
        this.farlands$drainCompleted();
        net.minecraft.world.entity.player.Player player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            this.farlands$submit(0L, 0L, false);
        } else {
            ExactPosition exact = (ExactPosition) player;
            this.farlands$submit(exact.farlands$cellCoord(ExactPosition.POS_X) >> 4,
                    exact.farlands$cellCoord(ExactPosition.POS_Z) >> 4, true);
        }
        this.commit();
    }

    /**
     * 取走完成结果：校验代际与版本，通过则上传，否则丢弃。
     *
     * <p>
     * 代际不符说明关卡换过，结果没有落点。版本不符说明烘制期间这一段的内容又变了，把键放回待烘等下一轮拿新内容。
     * 段已不在表里说明它被卸载了，几何由卸载那条路径释放过。
     */
    private void farlands$drainCompleted() {
        SimulationMeshPool.Completed done;
        while ((done = SimulationMeshPool.take()) != null) {
            BakeSnapshot snapshot = done.snapshot();
            SectionKey key = snapshot.key();
            if (snapshot.generation() != this.generation) {
                SimulationMesher.release(done.mesh());
                continue;
            }
            SimulationSection section = this.sections.get(key);
            if (section == null) {
                SimulationMesher.release(done.mesh());
                continue;
            }
            if (section.revision() != snapshot.revision()) {
                // 内容在烘制期间变过：结果作废，tint 缓存也可能被那次烘焙写进了旧值，一并清掉再排回待烘。
                SimulationMesher.release(done.mesh());
                section.clearTints();
                this.pending.add(key);
                continue;
            }
            if (done.mesh() == null) {
                SimulationGeometry.instance().release(key);
            } else {
                SimulationGeometry.instance().upload(key, done.mesh());
            }
        }
    }

    /**
     * 选最近的一批提交到烘焙池。
     *
     * <p>
     * 先进先出在半径 32 时会让脚下的草等半分钟，按距离取之后近的先出。距离取平方，与圆盘判据同一把尺，不必开方，
     * 也不会因为量纲不同把四个斜角排到后面。
     *
     * <p>
     * 每 tick 提交量受两个上限夹住：一段一批的配额，与烘焙池的空余名额。后者存在的理由不是省内存，而是防积压，
     * 一段的内容在途期间可能又变了。
     *
     * <p>
     * 无玩家基准时不排序，按 {@code pending} 的迭代顺序取：段号在模拟之地是两亿级，拿 {@code (0, 0)} 当中心会把
     * 远处的列排到前面。
     */
    private void farlands$submit(long centerX, long centerZ, boolean positioned) {
        int quota = Math.min(BUILD_PER_TICK, SimulationMeshPool.freeSlots());
        if (quota <= 0 || this.pending.isEmpty()) {
            return;
        }
        int want = Math.min(quota, this.pending.size());
        SectionKey[] picked = new SectionKey[want];
        int found;
        if (positioned) {
            found = this.farlands$pickNearest(picked, centerX, centerZ);
        } else {
            found = 0;
            for (SectionKey key : this.pending) {
                if (found == want) {
                    break;
                }
                picked[found++] = key;
            }
        }
        for (int i = 0; i < found; i++) {
            SectionKey key = picked[i];
            this.pending.remove(key);
            SimulationSection section = this.sections.get(key);
            if (section == null) {
                continue;
            }
            if (section.count() == 0) {
                SimulationGeometry.instance().release(key);
                continue;
            }
            SimulationMeshPool.submit(this.farlands$snapshot(key, section));
        }
    }

    /**
     * 把最近的 {@code picked.length} 个待烘段填进 {@code picked}，返回实际填了几个。
     *
     * <p>
     * 选取用容量等于本批配额的固定最大堆，堆顶是已选中最远的一个。候选只与堆顶比一次，近了才替换并下沉。逐候选
     * 线性找最大在配额上百、待烘上万时是千万次比较，与烘制本身同量级。
     */
    private int farlands$pickNearest(SectionKey[] picked, long centerX, long centerZ) {
        int want = picked.length;
        long[] distances = new long[want];
        int size = 0;
        for (SectionKey key : this.pending) {
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
        Neighborhood neighborhood = this.neighborhoods.computeIfAbsent(key, k -> new Neighborhood());
        if (neighborhood.version != this.structureVersion) {
            // 段集合增删过：段引用全部重查一次，再记下当时的版本。
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int slot = (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
                        SimulationSection neighbor = this.sectionOrNull(key.x() + dx, key.y() + dy, key.z() + dz);
                        neighborhood.sections[slot] = neighbor;
                        neighborhood.contents[slot] = neighbor == null ? null : neighbor.content();
                    }
                }
            }
            neighborhood.version = this.structureVersion;
        } else {
            // 结构没变，但邻段内容可能换过：27 次内容引用读，比 27 次查表加键分配便宜。
            for (int i = 0; i < Neighborhood.SLOTS; i++) {
                SimulationSection neighbor = neighborhood.sections[i];
                neighborhood.contents[i] = neighbor == null ? null : neighbor.content();
            }
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        return new BakeSnapshot(key, this.generation, section.revision(), section, section.content(),
                neighborhood.contents.clone(), this.defaultBiome, minecraft.getBlockColors(),
                minecraft.getModelManager().getBlockStateModelSet(), this.cardinalLighting,
                minecraft.options.ambientOcclusion().get(), minecraft.options.biomeBlendRadius().get());
    }

    /**
     * 一段的 27 格邻域缓存：段引用与它们最近一次看到的内容。
     *
     * <p>
     * 段引用只在 {@code structureVersion} 变化时重查；内容每次都按引用刷新一遍，因为邻段换内容不会改变段集合。
     * 这样一次取快照是 27 次字段读，而不是 27 次哈希查表加 27 个键分配。
     */
    private static final class Neighborhood {

        static final int SLOTS = 27;

        final SimulationSection[] sections = new SimulationSection[SLOTS];

        final SimulationSection.Content[] contents = new SimulationSection.Content[SLOTS];

        int version = Integer.MIN_VALUE;
    }

    /** 取或建一段。键已在手上时用这一支，省一次键构造。 */
    public SimulationSection section(SectionKey key) {
        SimulationSection section = this.sections.get(key);
        if (section == null) {
            section = new SimulationSection(key.x(), key.y(), key.z(), this.biomeFactory, this.defaultBiome);
            this.sections.put(key, section);

            this.structureVersion++;
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
        SimulationGeometry.instance().release(key);
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
        this.neighborhoods.clear();
        this.structureVersion++;

        this.snapshot = new Snapshot();
        this.generation++;
        SimulationMeshPool.discardCompleted();
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
        Set<SnapSection> live = this.snapshot.sectionsMutable();
        for (SectionKey key : this.snapDirty) {
            SimulationSection section = this.sections.get(key);
            if (section == null || !section.eligible()) {
                continue;
            }
            SimulationSection.Dirty dirty = section.takeDirty();
            if (dirty != null) {
                this.farlands$requeueGeometry(section, dirty, reach);
            }
            if (section.count() == 0) {
                SnapSection stale = section.snap();
                if (stale != null) {
                    live.remove(stale);
                    section.clearSnap();
                }
                continue;
            }
            if (section.snap() == null) {
                section.rebuild();
                SnapSection fresh = section.snap();
                if (fresh != null) {
                    live.add(fresh);
                }
            }
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
     * 只放回存在的段：未建的段放回去也只会在烘制时被跳过。
     */
    private void farlands$requeueGeometry(SimulationSection section, SimulationSection.Dirty dirty, int reach) {
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
                    if (this.sections.containsKey(key)) {
                        this.pending.add(key);
                    }
                }
            }
        }
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
     * 渲染列表。内容按段存在，列表只记「哪些段有内容」，所以只随段的空与非空转换增删，不随内容变化重建。
     *
     * <p>
     * 客户端 tick 与渲染抽取在同一个线程（都是 Render thread），所以这里用可变集合而不发布不可变副本：一次增删是
     * O(1)，而重建一份两万多元素的列表是每 tick 一次 O(段数)。
     */
    public static final class Snapshot {

        private final Set<SnapSection> sections = new java.util.LinkedHashSet<>();

        Snapshot() {
        }

        /** 渲染与碰撞读它；只在主线程上读写。 */
        public Set<SnapSection> sections() {
            return this.sections;
        }

        Set<SnapSection> sectionsMutable() {
            return this.sections;
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
