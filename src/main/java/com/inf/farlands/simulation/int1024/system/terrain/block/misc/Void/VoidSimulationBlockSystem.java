package com.inf.farlands.simulation.int1024.system.terrain.block.misc.Void;

import com.inf.farlands.simulation.int1024.SimulationBlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import love.crysh.long2.ints.Int1024;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * VOID 逐块系统：每格都是空气。
 *
 * <p>
 * 用途是把逐块路径跑起来：地形族选它之后管线走 BIOME 到 NOISE 到 LIGHTED，段里没有方块，所以能单独验「分派认到了
 * 逐块这一支」。它不读坐标也不写方块，是这条路径的最小实现。
 */
public final class VoidSimulationBlockSystem implements SimulationBlockSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidSimulationBlockSystem(SystemArgs args) {
    }

    @Override
    public BlockState fillBlock(Int1024 x, Int1024 y, Int1024 z) {
        return Blocks.AIR.defaultBlockState();
    }
}
