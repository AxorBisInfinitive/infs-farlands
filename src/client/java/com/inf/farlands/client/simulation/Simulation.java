package com.inf.farlands.client.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.inf.farlands.client.render.simulation.SimulationGeometry;
import com.inf.farlands.simulation.SimulationSectionKey;
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

    /** 段集合有增删时置真，提交要换快照。 */
    private boolean structureChanged;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

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

    /** 每 tick 烘几段几何，把建立成本摊开。取最近的那几段。 */
    private static final int BUILD_PER_TICK = 8;

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
        this.structureChanged = true;
        this.snapshot = Snapshot.EMPTY;
        SimulationGeometry.instance().clear();
    }

    /**
     * 应用服务端送来的一段。
     *
     * <p>
     * 段不存在就建，已存在就整体替换；随后入待烘集合并置结构变更，由下一次 {@link #commit} 发布新快照。
     * 只在客户端主线程调，与服务端那段包的处理点同线程。
     */
    public void applySection(SimulationSectionKey key, LevelChunkSection container,
            int shapeMode, DataLayer blockLight, DataLayer skyLight) {
        SectionKey local = new SectionKey(key.x(), key.y(), key.z());
        SimulationSection section = this.section(local);
        section.applyContainer(container, shapeMode, blockLight, skyLight);
        this.pending.add(local);
        this.structureChanged = true;
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
        if (this.sections.remove(key) != null) {
            this.structureChanged = true;
        }
        this.pending.remove(key);
        this.farlands$requeueNeighbors(key);
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
     * 每客户端 tick 一次：按距离限量烘几何、发布快照。
     *
     * <p>
     * 绑定与解绑不在这里：段集合完全由服务端的包驱动。这里只负责把待烘的那批烘掉，再按脏段重建快照。
     */
    public void tickSimulation() {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = minecraft.player;
        if (player == null) {
            this.farlands$buildNearest();
        } else {
            ExactPosition exact = (ExactPosition) player;
            long centerX = exact.farlands$cellCoord(ExactPosition.POS_X) >> 4;
            long centerZ = exact.farlands$cellCoord(ExactPosition.POS_Z) >> 4;
            this.farlands$buildNearest(centerX, centerZ);
        }
        this.commit();
    }

    /**
     * 从待烘集合里按到中心的平方距离取最近的一批，烘完上传。
     *
     * <p>
     * 先进先出在半径 32 时会让脚下的草等半分钟，按距离取之后近的先出。距离取平方，与圆盘判据同一把尺，
     * 不必开方，也不会因为量纲不同把四个斜角排到后面。
     */
    private void farlands$buildNearest(long centerX, long centerZ) {
        if (this.pending.isEmpty()) {
            return;
        }
        int want = Math.min(BUILD_PER_TICK, this.pending.size());
        SectionKey[] picked = new SectionKey[want];
        long[] distances = new long[want];
        int found = 0;
        for (SectionKey key : this.pending) {
            long dx = key.x() - centerX;
            long dz = key.z() - centerZ;
            long distance = dx * dx + dz * dz;
            if (found < want) {
                picked[found] = key;
                distances[found] = distance;
                found++;
                continue;
            }
            int worst = 0;
            for (int i = 1; i < want; i++) {
                if (distances[i] > distances[worst]) {
                    worst = i;
                }
            }
            if (distance < distances[worst]) {
                picked[worst] = key;
                distances[worst] = distance;
            }
        }
        this.farlands$bake(picked, found);
    }

    /**
     * 无玩家基准时的同一批烘制：按 {@code pending} 的迭代顺序取，不排序。
     *
     * <p>
     * 不拿 {@code (0, 0)} 当中心：段号在模拟之地是两亿级，{@code Math.abs(段号 - 0)} 会溢出成负数，把远处的列排到前面。
     */
    private void farlands$buildNearest() {
        if (this.pending.isEmpty()) {
            return;
        }
        int want = Math.min(BUILD_PER_TICK, this.pending.size());
        SectionKey[] picked = new SectionKey[want];
        int found = 0;
        for (SectionKey key : this.pending) {
            if (found == want) {
                break;
            }
            picked[found++] = key;
        }
        this.farlands$bake(picked, found);
    }

    /** 烘制并上传选中的列。空段与空网格一并释放旧几何。 */
    private void farlands$bake(SectionKey[] picked, int found) {
        for (int i = 0; i < found; i++) {
            SectionKey key = picked[i];
            this.pending.remove(key);
            SimulationSection section = this.sections.get(key);
            if (section == null || section.count() == 0) {
                SimulationGeometry.instance().release(key);
                continue;
            }
            SimulationMesher.Mesh mesh = SimulationMesher.build(section, this);
            if (mesh != null) {
                SimulationGeometry.instance().upload(key, mesh);
            } else {
                SimulationGeometry.instance().release(key);
            }
        }
    }

    /** 取或建一段。键已在手上时用这一支，省一次键构造。 */
    public SimulationSection section(SectionKey key) {
        SimulationSection section = this.sections.get(key);
        if (section == null) {
            section = new SimulationSection(key.x(), key.y(), key.z(), this.biomeFactory, this.defaultBiome);
            this.sections.put(key, section);
            this.structureChanged = true;
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
        this.structureChanged = true;
        this.snapshot = Snapshot.EMPTY;
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
        boolean changed = this.structureChanged;
        this.structureChanged = false;
        int reach = Math.max(2, net.minecraft.client.Minecraft.getInstance().options.biomeBlendRadius().get());
        List<SnapSection> out = new ArrayList<>(this.sections.size());
        for (SimulationSection section : this.sections.values()) {
            if (!section.eligible()) {
                continue;
            }
            SimulationSection.Dirty dirty = section.takeDirty();
            if (dirty != null) {
                section.rebuild();
                changed = true;
                this.farlands$requeueGeometry(section, dirty, reach);
            }
            SnapSection snap = section.snap();
            if (snap != null) {
                out.add(snap);
            }
        }
        if (changed) {
            this.snapshot = new Snapshot(List.copyOf(out));
        }
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

    /** 一次发布的不可变快照。 */
    public static final class Snapshot {

        public static final Snapshot EMPTY = new Snapshot(List.of());

        private final List<SnapSection> sections;

        Snapshot(List<SnapSection> sections) {
            this.sections = sections;
        }

        public List<SnapSection> sections() {
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
