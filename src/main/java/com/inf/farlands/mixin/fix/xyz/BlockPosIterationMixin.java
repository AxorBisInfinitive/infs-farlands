package com.inf.farlands.mixin.fix.xyz;

import com.google.common.collect.AbstractIterator;

import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 逐格迭代的格跨度在溢出量级上截断。
 *
 * <p>
 * {@code betweenClosed} 拿六个整数格端点算三轴跨度，乘积当迭代总次数。盒的两端一正一负无穷时，端点分别饱和到
 * int 两端，跨度是负数，乘积经 int 回绕成二十亿量级的正数，迭代器就此跑满两亿次以上。同一族的调用点是
 * {@code BlockGetter.forEachBlockIntersectedBetween}，它在实体流体与移动判定里每个 tick 都会走到。
 *
 * <p>
 * 判据与 {@code BlockCollisionsMixin} 同一套：三轴跨度都为正、每轴不超过 {@link #AXIS_LIMIT}、乘积不超过
 * {@link #CELL_LIMIT} 时按原版算术逐字执行；否则把上端点收到 {@code min + 64 - 1}，按前缀迭代。
 *
 * <p>
 * 覆写而不是注入：跨度算在方法体内，匿名迭代器捕获的是这几个局部量，注入拿不到它们的替换点。覆写体与反编译
 * {@code BlockPos.java:405-427} 逐句对应，差别只有进入迭代器之前那一次截断。
 */
@Mixin(BlockPos.class)
public class BlockPosIterationMixin {

    /** 单轴跨度上限，超过即认为原版算术已经溢出。 */
    private static final int AXIS_LIMIT = 1024;

    /** 三元乘积上限，同上。 */
    private static final long CELL_LIMIT = 262144L;

    /** 截断后每轴的实际跨度。 */
    private static final int PREFIX_SPAN = 64;

    @Overwrite
    public static Iterable<BlockPos> betweenClosed(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long rawWidth = (long) maxX - minX + 1L;
        long rawHeight = (long) maxY - minY + 1L;
        long rawDepth = (long) maxZ - minZ + 1L;
        if (!farlands$representable(rawWidth, rawHeight, rawDepth)) {
            maxX = (int) Math.min((long) minX + PREFIX_SPAN - 1L, Integer.MAX_VALUE);
            maxY = (int) Math.min((long) minY + PREFIX_SPAN - 1L, Integer.MAX_VALUE);
            maxZ = (int) Math.min((long) minZ + PREFIX_SPAN - 1L, Integer.MAX_VALUE);
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int depth = maxZ - minZ + 1;
        int end = width * height * depth;
        int baseX = minX;
        int baseY = minY;
        int baseZ = minZ;
        return () -> new AbstractIterator<BlockPos>() {
            private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            private int index;

            protected BlockPos computeNext() {
                if (this.index == end) {
                    return this.endOfData();
                }

                int x = this.index % width;
                int slice = this.index / width;
                int y = slice % height;
                int z = slice / height;
                this.index++;
                return this.cursor.set(baseX + x, baseY + y, baseZ + z);
            }
        };
    }

    /**
     * 三轴跨度是否都落在原版算得对的范围内。
     *
     * <p>
     * 先判每轴，再判乘积：每轴不超过 {@code AXIS_LIMIT} 时乘积最多 {@code 2^30}，long 上不会溢出。
     */
    private static boolean farlands$representable(long width, long height, long depth) {
        if (width <= 0L || height <= 0L || depth <= 0L) {
            return false;
        }
        if (width > AXIS_LIMIT || height > AXIS_LIMIT || depth > AXIS_LIMIT) {
            return false;
        }
        return width * height * depth <= CELL_LIMIT;
    }
}
