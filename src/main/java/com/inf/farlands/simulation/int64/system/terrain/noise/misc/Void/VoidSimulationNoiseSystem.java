package com.inf.farlands.simulation.int64.system.terrain.noise.misc.Void;

import com.inf.farlands.simulation.int64.SimulationNoiseSystem;
import com.inf.farlands.simulation.int64.noise.Aquifer;
import com.inf.farlands.simulation.int64.noise.Density;
import com.inf.farlands.simulation.int64.noise.FluidPicker;
import com.inf.farlands.simulation.int64.noise.FluidStatus;
import com.inf.farlands.simulation.int64.noise.Random;
import com.inf.farlands.simulation.int64.noise.Router;
import com.inf.farlands.simulation.int64.noise.Settings;
import com.inf.farlands.simulation.int64.noise.instance.Constant;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 模拟层的 VOID 系统，与 farlands 的 {@code VoidNoiseSystem} 同形：什么都不生成。
 *
 * <p>
 * 三处口径照抄：{@code finalDensity} 恒负、液面查询恒空气、含水层恒 AIR。第一处让密度恒负，第二处让流体分支
 * 落成空气，第三处让材质规则在第一步就短路。
 *
 * <p>
 * 不吃参数，声明写 {@link SystemParams#EMPTY}：注册期要求每个实现类都有声明字段，没有声明不是一种状态。
 */
public final class VoidSimulationNoiseSystem implements SimulationNoiseSystem {

    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    public VoidSimulationNoiseSystem(SystemArgs args) {
    }

    @Override
    public Density createFinalDensity(Router router) {
        return new Constant(-1.0);
    }

    @Override
    public FluidPicker createFluidPicker(Settings settings) {
        // 液位取 Long.MIN_VALUE，FluidStatus.at 的判据是严格小于，因此任何 y 都取不到它，恒出空气。
        return (x, y, z) -> new FluidStatus(Long.MIN_VALUE, Blocks.AIR.defaultBlockState());
    }

    @Override
    public Aquifer createAquifer(Settings settings, long sectionX, long sectionY, long sectionZ, Router router,
            Random random, FluidPicker picker) {
        return new Aquifer() {

            @Override
            public BlockState computeSubstance(long x, long y, long z, double density) {
                return Blocks.AIR.defaultBlockState();
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return false;
            }
        };
    }
}
