package com.inf.farlands.simulation.int1024.system.terrain.block.misc.Void;

import com.inf.farlands.simulation.int1024.SimulationBlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import love.crysh.long2.binaries.binary1280.Binary1280;
import love.crysh.long2.binaries.binary256.Binary256;
import love.crysh.long2.binaries.binary640.Binary640;
import love.crysh.long2.ints.Int1024;
import love.crysh.long2.ints.Int256;
import love.crysh.long2.ints.Int512;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * VOID 逐块系统：每格都是空气。
 *
 * <p>
 * 用途是把逐块路径跑起来：地形族选它之后管线走 BIOME 到 TERRAIN 到 LIGHTED，段里没有方块，所以能单独验「分派认到了
 * 逐块这一支」。它不读坐标也不写方块，是这条路径的最小实现。
 */
public final class VoidSimulationBlockSystem implements SimulationBlockSystem {

    /**
     * 临时测试参数：六个宽度类型各一个框，用来验界面输入、语法错标红、落盘、读回与预填这一圈。
     *
     * <p>
     * 本系统自己一个都不读，所以它们在功能上无用，只承担「有声明就要建框」这一条。验完即删，删除时连同上面对应的
     * import 一起去掉，另开一次改动。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofInt256("int256").define(Int256.parseInt("12345678901234567890")).build(),
            SystemParamSpec.ofInt512("int512").define(Int512.parseInt("-123456789012345678901234567890")).build(),
            SystemParamSpec.ofInt1024("int1024")
                    .define(Int1024.parseInt("1234567890123456789012345678901234567890")).build(),
            SystemParamSpec.ofBinary256("binary256").define(Binary256.of("1.5")).build(),
            SystemParamSpec.ofBinary640("binary640").define(Binary640.of("-1.5e-300")).build(),
            SystemParamSpec.ofBinary1280("binary1280").define(Binary1280.of("1.5e3000")).build());

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidSimulationBlockSystem(SystemArgs args) {
    }

    @Override
    public BlockState fillBlock(Int1024 x, Int1024 y, Int1024 z) {
        return Blocks.AIR.defaultBlockState();
    }
}
