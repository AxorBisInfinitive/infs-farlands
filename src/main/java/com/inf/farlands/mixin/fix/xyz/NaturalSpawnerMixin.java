package com.inf.farlands.mixin.fix.xyz;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.window.PlayerTable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 刷怪取点与粗略群系查询的极端 Y 适配。
 *
 * <p>
 * {@code getRandomPosWithin} 原版取 {@code [level.getMinY(), WORLD_SURFACE + 1]}
 * 的随机 Y。本 port
 * 的地形按窗口生成、fill 把高度图直接写在窗口的 Y 上，所以窗口滑到极端 Y 之后这个区间宽达数千万
 * 到二十亿格：每次刷怪尝试都落在一个全新的 Y 上，既白消耗，又把该 Y 的段与群系数据带出来。
 *
 * <p>
 * 这里把随机区间夹到本维度内离地表最近的玩家窗口带，与 {@code shouldTickBlocksAt} 的窗口语义
 * 一致，窗口之外不再取点。窗口带与 {@code [下界, 地表]} 无交集时退回原区间。溢出夹取保留：
 * 区间宽度必须落在 int 内，否则 {@code max - min + 1} 回绕成负数，{@code randomBetweenInclusive}
 * 里的 {@code nextInt} 会抛。
 *
 * <p>
 * {@code getRoughBiome} 对 LevelChunk 改走 {@code getUncachedNoiseBiome}。原版走
 * {@code ChunkAccess.getNoiseBiome}，而 3 参 {@code Level.getNoiseBiome} 只是
 * {@code LevelReader}
 * 的默认实现，内部仍然落到 chunk 的段取数；本 port 那个覆写会按需建段，于是每次刷怪尝试都能建出
 * 一个窗口外的段。{@code get。UncachedNoiseBiome} 是 {@code LevelReader} 上真正的未缓存入口，
 * 服务端实现直接问生成器的群系源
 */
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {

    @Overwrite
    private static Biome getRoughBiome(BlockPos pos, ChunkAccess chunk) {
        int x = QuartPos.fromBlock(pos.getX());
        int y = QuartPos.fromBlock(pos.getY());
        int z = QuartPos.fromBlock(pos.getZ());
        if (chunk instanceof LevelChunk levelChunk) {
            return levelChunk.getLevel().getUncachedNoiseBiome(x, y, z).value();
        }
        return chunk.getNoiseBiome(x, y, z).value();
    }

    @Overwrite
    private static BlockPos getRandomPosWithin(Level level, LevelChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int x = cpos.getMinBlockX() + level.getRandom().nextInt(16);
        int z = cpos.getMinBlockZ() + level.getRandom().nextInt(16);
        int topEmptyY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;

        int lo = level.getMinY();
        int hi = (int) Math.min((long) topEmptyY, (long) lo + Integer.MAX_VALUE - 1L);
        if (hi < lo) {
            hi = lo;
        }

        // 窗口带：取离地表最近的玩家窗口，把取点区间夹进去。section 坐标左移 4 位在极值会越过
        // int 下界，全程用 long 算，夹完再落回 int。界外玩家不参与：他们那里没有区块。
        int half = FarlandsConfig.verticalSimulationDistance;
        long bandLo = 0L;
        long bandHi = 0L;
        long bestDist = Long.MAX_VALUE;
        if (level instanceof ServerLevel serverLevel) {
            java.util.List<PlayerTable.Entry> entries = PlayerTable.entries();
            for (int index = 0; index < entries.size(); index++) {
                PlayerTable.Entry entry = entries.get(index);
                if (!entry.inBounds() || !entry.dimension().equals(serverLevel.dimension())) {
                    continue;
                }
                int center = entry.sectionCenterY();
                long rLo = ((long) center - half) << 4;
                long rHi = (((long) center + half) << 4) + 15L;
                long d = hi < rLo ? rLo - hi : (hi > rHi ? hi - rHi : 0L);
                if (d < bestDist) {
                    bestDist = d;
                    bandLo = rLo;
                    bandHi = rHi;
                }
            }
        }
        if (bestDist != Long.MAX_VALUE) {
            long wLo = Math.max((long) lo, bandLo);
            long wHi = Math.min((long) hi, bandHi);
            if (wLo <= wHi) {
                lo = (int) wLo;
                hi = (int) wHi;
            }
        }

        int y = Mth.randomBetweenInclusive(level.getRandom(), lo, hi);
        return new BlockPos(x, y, z);
    }
}
