package com.inf.farlands.simulation;

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
}
