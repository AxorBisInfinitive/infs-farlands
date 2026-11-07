package com.inf.farlands.simulation.model;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 服务端的一段模拟数据：块与群系容器，加两层光照与形状模式。
 *
 * <p>
 * 块与群系用 {@link LevelChunkSection} 承载，线上格式与体积都直接借它，不另造一套。光照是两层
 * {@link DataLayer}，四比特一格，默认 0 时是同质未分配层，不占数组。
 *
 * <p>
 * 形状模式是段级的：{@link #SHAPE_FULL} 表示碰撞与轮廓恒取整格，{@link #SHAPE_DERIVED} 表示按状态派生。
 * 客户端据此决定用哪个构造器造模拟方块。容器里只有状态，形状不是状态的一部分，所以只能段级表达。
 */
public final class SimulationSectionData {

    /** 段边长。 */
    public static final int SIZE = 16;

    /** 形状按状态派生。 */
    public static final int SHAPE_DERIVED = 0;

    /** 形状整格：碰撞与轮廓都取满格。 */
    public static final int SHAPE_FULL = 1;

    public final LevelChunkSection section;

    public DataLayer blockLight = new DataLayer();

    public DataLayer skyLight = new DataLayer();

    /**
     * 阶段，取值见 {@link SimulationSectionStage}。建段时是 {@code UNPROCESSED}，内容写完之后推到
     * {@code PROCESSED}，此后不再变。
     *
     * <p>
     * volatile 只作保险：今天的写方与读方都在服务端主线程，容器内容的可见性由提交到主线程那一步的队列边界给出。
     * 标上它，将来若有人让池线程直接升档，也不会静默丢掉这条边界。
     */
    private volatile int stage;

    private boolean persistDirty;

    private boolean releasing;

    private int shapeMode;

    private boolean dirty;

    public SimulationSectionData(LevelChunkSection section) {
        this.section = section;
    }

    public int shapeMode() {
        return this.shapeMode;
    }

    public void setShapeMode(int mode) {
        this.shapeMode = mode;
    }

    /** 当前阶段。 */
    public int stage() {
        return this.stage;
    }

    /**
     * 把阶段推到 target。单调不减，回退请求被丢弃。
     *
     * <p>
     * 与地形侧 {@code SectionStage.setStage} 的单调闸同一个理由：推进点不止一处时，迟到的低档写会把已经前进的
     * 段改回去。
     */
    public void advanceTo(int target) {
        if (target > this.stage) {
            this.stage = target;
        }
    }

    /** 该段自上次下发以来是否变过。 */
    public boolean isDirty() {
        return this.dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    /**
     * 该段自上次落盘以来是否变过。
     *
     * <p>
     * 与 {@link #dirty} 是两个位，不能合并。那个是「自上次下发以来」，所有想要它的玩家都收到就清，复用它会让
     * 段在第一次下发成功之后再也不会被写盘。
     *
     * <p>仅主线程读写。
     */
    public boolean isPersistDirty() {
        return this.persistDirty;
    }

    public void markPersistDirty() {
        this.persistDirty = true;
    }

    public void clearPersistDirty() {
        this.persistDirty = false;
    }

    /**
     * 该段已离开视距并提交了落盘，等写提交完成才摘条目。
     *
     * <p>
     * 置真期间同 key 的段仍留在表里，所以玩家在写提交完成前又走回来时，命中还是同一个对象，既不丢数据也不
     * 重复生产。仅主线程读写。
     */
    public boolean isReleasing() {
        return this.releasing;
    }

    public void markReleasing() {
        this.releasing = true;
    }

    public void clearReleasing() {
        this.releasing = false;
    }
}
