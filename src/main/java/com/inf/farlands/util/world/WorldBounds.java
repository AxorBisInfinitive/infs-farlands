package com.inf.farlands.util.world;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.FarlandsConstant;

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
     * 方块坐标是否在可表示范围内，即 int 全域。比可玩界两端各宽 16 格。
     */
    public static boolean inBlockAbsolute(long v) {
        return v >= ~FarlandsConstant.MAX_BLOCK && v <= FarlandsConstant.MAX_BLOCK;
    }

    /**
     * 段号是否在可玩段范围内。判据与 inChunk 同值，量纲不同：本方法吃段号 sy，
     * inChunk 吃区块 xz。
     */
    public static boolean inSection(int sy) {
        return sy >= MIN_PLAYABLE_SECTION && sy <= MAX_PLAYABLE_SECTION;
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

    public static boolean inBuildHeight(int y) {
        return y >= FarlandsConfig.worldGenMinY && y < FarlandsConfig.worldGenMaxY;
    }

    /** 范围 [minY, maxY) 是否完全在可玩高度内，沿用 hasChunksAt 语义。 */
    public static boolean inBuildHeightRange(int minY, int maxY) {
        return minY >= FarlandsConfig.worldGenMinY && maxY < FarlandsConfig.worldGenMaxY;
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
