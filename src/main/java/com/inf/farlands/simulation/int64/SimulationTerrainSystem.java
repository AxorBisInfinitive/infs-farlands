package com.inf.farlands.simulation.int64;

import com.inf.farlands.simulation.int64.noise.Aquifer;
import com.inf.farlands.simulation.int64.noise.FluidPicker;
import com.inf.farlands.simulation.int64.noise.Random;
import com.inf.farlands.simulation.int64.noise.Router;
import com.inf.farlands.simulation.int64.noise.Settings;
import com.inf.farlands.simulation.int64.util.LongBox;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 地形系统基接口，对应 terrain 的 {@code TerrainSystem}。
 *
 * <p>方法名、默认值、以及 null 的含义都照抄那一份：{@code createFluidPicker} 与 {@code createAquifer}
 * 返回 null 表示「用管线默认」。差别只有坐标宽度与实参：vanilla 的 {@code createAquifer} 收 {@code NoiseChunk}，
 * 它顺带提供 {@code preliminarySurfaceLevel}，这里改从 {@link Router} 取。
 */
public interface SimulationTerrainSystem {

    /** 管线 fill 的液面查询。null 表示用管线默认。 */
    default FluidPicker createFluidPicker(Settings settings) {
        return null;
    }

    /** 管线 fill 的含水层。null 表示用默认含水层。 */
    default Aquifer createAquifer(Settings settings, long sectionX, long sectionY, long sectionZ,
            Router router, Random random, FluidPicker picker) {
        return null;
    }

    /** 密度为正、且含水层与矿脉都没有接管时的落点。 */
    default BlockState defaultBlock(Settings settings) {
        return settings.defaultBlock();
    }

    /** 一次填充开始时的回调。当前无调用方。 */
    default void onChunkFillStart(LongBox box) {
    }
}
