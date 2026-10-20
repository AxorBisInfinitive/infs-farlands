package com.inf.farlands.terrain.system.terrain.block.misc.V;

import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.util.hash.HashMath;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 逐块哈希的 V 形图案：坐标的 64 位哈希能被 0x56 整除的格子放一个灵魂系列方块，是哪一个由三轴之和除以
 * 6 的余数决定。
 *
 * <p>
 * 求和与取模都用 long 与 {@link Math#floorMod}：三轴之和在 int 上会回绕，而负和用 % 会得到负余数，索引
 * 直接越界。
 *
 * <p>
 * 六种方块里只有灵魂营火带方块实体。填充是直写 section，不建方块实体，所以营火只作装饰，不烹饪。同理，
 * 火把、灯笼、火与营火的支撑判据只在相邻更新时求值，直写不触发，它们会一直站着，直到附近的格子被改动。
 *
 * <p>
 * 无状态：{@link BlockSystem#fillBlock} 是纯函数，图案只依赖坐标，没有种子，因此同一个坐标在每个世界与
 * 每个维度放出的方块完全相同。
 */
public final class VBlockSystem implements BlockSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** 余数到方块的映射，顺序即放置顺序。 */
    private static final BlockState[] CHOICES = {
            Blocks.SOUL_SAND.defaultBlockState(),
            Blocks.SOUL_SOIL.defaultBlockState(),
            Blocks.SOUL_FIRE.defaultBlockState(),
            Blocks.SOUL_TORCH.defaultBlockState(),
            Blocks.SOUL_LANTERN.defaultBlockState(),
            Blocks.SOUL_CAMPFIRE.defaultBlockState(),
    };

    /** 统一构造签名，本系统不读任何参数。 */
    public VBlockSystem(SystemArgs args) {
    }

    @Override
    public BlockState fillBlock(int x, int y, int z) {
        if (HashMath.hash(x, y, z) % 0x56 != 0) {
            return AIR;
        }
        long sum = (long) x + (long) y + (long) z;
        return CHOICES[Math.floorMod(sum, CHOICES.length)];
    }
}
