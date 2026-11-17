package com.inf.farlands.util.world;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.FarlandsConstant;
import com.inf.long2.ints.Int1024;

import net.minecraft.world.phys.Vec3;

/**
 * 边界统一语义。
 */
public final class WorldBounds {
    private WorldBounds() {
    }

    /** 正方向最后一个可玩方块 = 2,147,483,631 = 2^31-17。 */
    public static final int MAX_PLAYABLE_BLOCK = FarlandsConstant.MAX_PLAYABLE_BLOCK;

    public static final int MIN_PLAYABLE_BLOCK = ~FarlandsConstant.MAX_PLAYABLE_BLOCK;

    /** 正方向最后一个可表示段 = 134,217,727 = 2^27-1，段号为 sy。 */
    public static final int MAX_SECTION = FarlandsConstant.MAX_SECTION;

    public static final int MIN_SECTION = FarlandsConstant.MIN_SECTION;

    /** 正方向最后一个可玩段 = 134,217,726 = 2^27-2。 */
    public static final int MAX_PLAYABLE_SECTION = FarlandsConstant.MAX_PLAYABLE_SECTION;

    public static final int MIN_PLAYABLE_SECTION = ~FarlandsConstant.MAX_PLAYABLE_SECTION;

    /** 可玩段界的 {@link Int1024} 形态，界值与上面四个 int 常量同源。 */
    private static final Int1024 PLAYABLE_SECTION_MIN = Int1024.of((long) MIN_PLAYABLE_SECTION);

    private static final Int1024 PLAYABLE_SECTION_MAX = Int1024.of((long) MAX_PLAYABLE_SECTION);

    /** 可表示方块界的 {@link Int1024} 形态，界值与 `inBlockAbsolute(long)` 同源。 */
    private static final Int1024 ABSOLUTE_MIN = Int1024.of((long) ~FarlandsConstant.MAX_BLOCK);

    private static final Int1024 ABSOLUTE_MAX = Int1024.of((long) FarlandsConstant.MAX_BLOCK);

    public static boolean inBlock(int v) {
        return v >= MIN_PLAYABLE_BLOCK && v <= MAX_PLAYABLE_BLOCK;
    }

    public static boolean inBlock(long v) {
        return v >= MIN_PLAYABLE_BLOCK && v <= MAX_PLAYABLE_BLOCK;
    }

    public static boolean inBlockXZ(int x, int z) {
        return inBlock(x) && inBlock(z);
    }

    public static boolean inBlockXZ(long x, long z) {
        return inBlock(x) && inBlock(z);
    }

    /**
     * 三轴是否都在可玩界内，任一轴界外即为假。与 {@link #inBlockXZ} 同族，只是把第三轴也收进来。
     *
     * <p>
     * 判界的调用点一律用它，不要各自写三行 "或"。同一个语义在三处各写一遍，改一处就漏两处。
     */
    public static boolean inBlock(long x, long y, long z) {
        return inBlock(x) && inBlock(y) && inBlock(z);
    }

    /**
     * 方块坐标是否在可表示范围内，即 int 全域。比可玩界两端各宽 16 格。
     */
    public static boolean inBlockAbsolute(long v) {
        return v >= ~FarlandsConstant.MAX_BLOCK && v <= FarlandsConstant.MAX_BLOCK;
    }

    /**
     * 方块坐标是否在可表示范围内，方块坐标是 {@link Int1024}。
     *
     * <p>
     * 与 {@link #inBlockAbsolute(long)} 同界，宽度不同：那一支到 long 为止。1024 域的坐标用本方法，否则界外的坐标会被
     * 窄化后才比较，判成界内。
     */
    public static boolean inBlockAbsolute(Int1024 v) {
        return v.compareTo(ABSOLUTE_MIN) >= 0 && v.compareTo(ABSOLUTE_MAX) <= 0;
    }

    /** 三轴方块坐标是否都在可表示范围内，任一轴界外即为假。 */
    public static boolean inBlockAbsolute(Int1024 x, Int1024 y, Int1024 z) {
        return inBlockAbsolute(x) && inBlockAbsolute(y) && inBlockAbsolute(z);
    }

    /**
     * 三轴方块坐标是否都在可表示范围内，坐标是 {@link Vec3}。
     *
     * <p>
     * 判据与 {@link #inBlockAbsolute(long)} 同界，入参是射线或实体的端点。非有限值一律算界外：
     * {@code (long) Math.floor(NaN)} 是 0，只按界值比较会把非有限坐标判成界内。
     *
     * <p>
     * 这一族判据有两个消费点，射线检测与实体穿行的入口守卫，同一语义不各写一遍。
     */
    public static boolean inBlockAbsolute(Vec3 position) {
        return axisInBlockAbsolute(position.x) && axisInBlockAbsolute(position.y)
                && axisInBlockAbsolute(position.z);
    }

    /** 单轴是 {@link #inBlockAbsolute(Vec3)} 的分量。 */
    private static boolean axisInBlockAbsolute(double value) {
        return Double.isFinite(value) && inBlockAbsolute((long) Math.floor(value));
    }

    /**
     * 段号是否在可玩段范围内。判据与 inChunk 同值，量纲不同：本方法吃段号 sy，
     * inChunk 吃区块 xz。
     */
    public static boolean inSection(int sy) {
        return sy >= MIN_PLAYABLE_SECTION && sy <= MAX_PLAYABLE_SECTION;
    }

    /**
     * 段号是否在可玩段界内，段号是 long。
     *
     * <p>
     * 按 long 比较，不窄化：段号可以超出 int，窄化会把 2^32 + 5 截成 5，把界外段判成界内。
     */
    public static boolean inSection(long sy) {
        return sy >= MIN_PLAYABLE_SECTION && sy <= MAX_PLAYABLE_SECTION;
    }

    /**
     * 三轴段号是否都在可玩段界内，任一轴界外即为假。与 {@link #inBlock(long, long, long)} 同族，量纲是段号。
     */
    public static boolean inSection(long x, long y, long z) {
        return inSection(x) && inSection(y) && inSection(z);
    }

    /**
     * 段号是否在可玩段界内，段号是 {@link Int1024}。
     *
     * <p>
     * 判据与 {@link #inSection(long)} 同值，宽度不同：那一支到 long 为止。模拟层枚举 1024 位段号时用本方法，否则
     * 段号超出 long 之后，窄化会把界外段判成界内。
     */
    public static boolean inSection(Int1024 sy) {
        return sy.compareTo(PLAYABLE_SECTION_MIN) >= 0 && sy.compareTo(PLAYABLE_SECTION_MAX) <= 0;
    }

    /** 三轴段号是否都在可玩段界内，段号是 {@link Int1024}。与 {@link #inSection(long, long, long)} 同族。 */
    public static boolean inSection(Int1024 x, Int1024 y, Int1024 z) {
        return inSection(x) && inSection(y) && inSection(z);
    }

    /**
     * 段号是否在可表示段范围内，即段内方块左移四位后不越过 int。比可玩段两端各宽一段，
     * 那两段的方块全部在可玩方块范围之外。
     */
    public static boolean inSectionAbsolute(int sy) {
        return sy >= MIN_SECTION && sy <= MAX_SECTION;
    }

    public static boolean inChunk(int v) {
        return v >= MIN_PLAYABLE_SECTION && v <= MAX_PLAYABLE_SECTION;
    }

    public static boolean inChunkRange(int cx, int cz) {
        return inChunk(cx) && inChunk(cz);
    }

    /** 可读写的绝对最小 Y：配置界与可玩方块界的交集下界。 */
    private static long readableMinY() {
        return Math.max((long) FarlandsConfig.worldGenMinY, (long) MIN_PLAYABLE_BLOCK);
    }

    /** 可读写的绝对最大 Y：配置界与可玩方块界的交集上界。配置界上界开区间，故减一。 */
    private static long readableMaxY() {
        return Math.min((long) FarlandsConfig.worldGenMaxY - 1L, (long) MAX_PLAYABLE_BLOCK);
    }

    /**
     * 该 Y 是否可读写：配置界与可玩方块界的交集。
     *
     * <p>
     * 只取配置界会留下 16 格缓冲带：可玩方块界到维度高之间，y 在配置界内而所有位置判据都认为那里没有方块。
     * 那条带里读门放行、窗口判据判无驱动者，于是 {@code Level.getBlockState} 一路走到
     * {@code getChunk} 的加载版并阻塞在一个永不完成的 FULL 上。取交集之后，读与写都在同一条界上，
     * 带内直接给 VOID_AIR 与 false，请求不会发出。
     *
     * <p>
     * 读门与写门都以 {@code Level.isInValidBounds} 为唯一入口，后者取本方法与
     * {@code ChunkPos.isValid}，所以窄化自动同时覆盖读与写。数据侧另有一条同宽的过滤：
     * {@code Scheduler.sectionsFor} 用 {@code inSection} 剔掉可玩段界之外的段，那条带里不会产出数据，
     * 因此不会出现「渲染看得见、读说没有」的裂缝。
     */
    public static boolean inBuildHeight(int y) {
        return y >= readableMinY() && y <= readableMaxY();
    }

    /** 范围 [minY, maxY) 是否完全在可读写高度内，沿用 hasChunksAt 语义。 */
    public static boolean inBuildHeightRange(int minY, int maxY) {
        return minY >= readableMinY() && (long) maxY - 1L <= readableMaxY();
    }

    // clamp 含边界，永不落缓冲带

    public static int clampBlockCoord(int v) {
        return v > MAX_PLAYABLE_BLOCK ? MAX_PLAYABLE_BLOCK
                : v < MIN_PLAYABLE_BLOCK ? MIN_PLAYABLE_BLOCK : v;
    }

    public static int clampBlockCoord(long v) {
        return v > MAX_PLAYABLE_BLOCK ? MAX_PLAYABLE_BLOCK
                : v < MIN_PLAYABLE_BLOCK ? MIN_PLAYABLE_BLOCK : (int) v;
    }

    public static int clampChunk(int v) {
        return v > MAX_PLAYABLE_SECTION ? MAX_PLAYABLE_SECTION
                : v < MIN_PLAYABLE_SECTION ? MIN_PLAYABLE_SECTION : v;
    }
}
