package com.inf.farlands.mixin.expand.xyz.pos;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkPos.class)
public abstract class ChunkPosMixin {

    @Shadow
    public int x;
    @Shadow
    public int z;

    @Unique
    private int shiftToBlockCoord(int coord) {
        long val = (long) coord << 4;
        if (val > Integer.MAX_VALUE - 15)
            return Integer.MAX_VALUE - 15;
        if (val < Integer.MIN_VALUE + 15)
            return Integer.MIN_VALUE + 15;
        return (int) val;
    }

    @Overwrite
    public int getMinBlockX() {
        return shiftToBlockCoord(this.x);
    }

    @Overwrite
    public int getMinBlockZ() {
        return shiftToBlockCoord(this.z);
    }

    /**
     * 区块坐标是否有效，判据取可玩段界。
     *
     * <p>
     * 原版是 {@code Mth.absMax(x, z) <= 1875000}，即 ±3000 万格，本 port 的世界远在其外，所以这一条曾被改成恒真。
     * 恒真的代价在 {@code Level.isInValidBounds}：它由「在建造高度内」与「区块坐标有效」两条腿组成，后者被短路
     * 之后，界外坐标的读写不再被拦下，一路走 {@code getChunk} 建 holder、加票，然后等一个本 port 永不补的 FULL，
     * 服务端停在那里。
     *
     * <p>
     * 判据取 {@code WorldBounds.inChunkRange}，即 {@code ±MAX_PLAYABLE_SECTION}。上界 134217726 覆盖方块
     * 2147483616 到 2147483631，正好到 {@code MAX_PLAYABLE_BLOCK}；再外面那一圈没有任何可读可写的方块。
     *
     * <p>
     * 这条谓词原先还兼任 {@code GenerationChunkHolder} 构造器的建造型断言，直接窄化会让出生区准备在
     * 134217727 那一圈抛 {@code Trying to create chunk out of reasonable bounds}。两个用法已经拆开：那条断言由
     * {@code GenerationChunkHolderMixin} 重定向成恒真，holder 仍可在 {@code MAX_SECTION} 那一圈建出来，供票圈与
     * 窗口把边界走完。改这里时不要以为它只影响读写门。
     */
    @Overwrite
    public static boolean isValid(int x, int z) {
        return WorldBounds.inChunkRange(x, z);
    }
}
