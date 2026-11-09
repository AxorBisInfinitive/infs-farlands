package com.inf.farlands.simulation.int64.terrainFiller;

import com.inf.farlands.simulation.SimulationSystem;
import com.inf.farlands.simulation.int64.SimulationNoiseSystem;
import com.inf.farlands.simulation.int64.noise.Aquifer;
import com.inf.farlands.simulation.int64.noise.CellSampler;
import com.inf.farlands.simulation.int64.noise.Density;
import com.inf.farlands.simulation.int64.noise.FluidPicker;
import com.inf.farlands.simulation.int64.noise.InterpolatedDensity;
import com.inf.farlands.simulation.int64.noise.Router;
import com.inf.farlands.simulation.int64.noise.Sampler;
import com.inf.farlands.simulation.int64.noise.Settings;
import com.inf.farlands.simulation.int64.noise.instance.Aquifers;
import com.inf.farlands.simulation.int64.noise.instance.Constant;
import com.inf.farlands.simulation.int64.noise.instance.FluidPickers;
import com.inf.farlands.simulation.int64.util.LongBox;
import com.inf.farlands.simulation.int64.util.Sink;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 段填充的全部逻辑，与 terrain 的 {@code AbstractTerrainFiller} 同形。
 *
 * <p>一次填充五步：按段换出 {@code Settings}，取系统给的路由，取液面查询与含水层并在返回 null 时落到默认件，
 * 遍历求值，最后由材质规则定方块。材质规则照抄 vanilla：含水层返回 {@code null} 表示实方块，取
 * {@code defaultBlock}；空气不写。
 *
 * <p>遍历有两条路径，由密度自己声明：实现 {@link InterpolatedDensity} 的走 {@link CellSampler}，
 * 先按 cell 网格算角点、再块内三线性插值；其余走 {@link Sampler} 的逐块求值。两条路径喂同一个
 * {@code SectionSink}，写入面完全相同。cell 路径要求 cell 边长整除一段，不成立时退回逐块。
 *
 * <p>系统从 {@link SimulationSystem} 现取，本类不认识当前是哪一个。terrain 那边从 {@code LevelSystems}
 * 取，这边从硬编码枚举取；将来系统按 level 分化时只改这一个取数点，接口不动。
 *
 * <p>跑在 {@code farlands-sim} 上，所以段写入只用五参 {@code setBlockState} 的 {@code false} 一档，不碰阶段、
 * 不碰段表、不标脏，那三件由 {@code SimulationLifecycle} 在主线程做。
 */
public abstract class AbstractSimulationFiller implements SimulationFiller {

    /**
     * 占位路由：十五个常量，只有 {@code finalDensity} 会被系统换掉。
     *
     * <p>仿真层没有维度给定的 vanilla 路由，而 {@code createRouter} 需要一份入参。今天没有任何系统读另外
     * 十四个字段；接真实含水层时按当时的口径重填。
     */
    private static final Router BASE_ROUTER = new Router(
            new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0),
            new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0),
            new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0));

    protected AbstractSimulationFiller() {
    }

    @Override
    public void fill(ServerLevel level, SimulationSectionKey key, SimulationSectionData data) {
        SimulationNoiseSystem system = SimulationSystem.SELECTED.system();
        Settings settings = prototypeFor(system).withRange(key.y() * 16L, 16L);
        Router router = system.createRouter(BASE_ROUTER);
        Density density = router.finalDensity();
        FluidPicker picker = system.createFluidPicker(settings);
        if (picker == null) {
            picker = FluidPickers.standard(settings.seaLevel(), settings.defaultFluid());
        }
        // Random 传 null：现有两个系统都不读它。接真实含水层时补一个实现。
        Aquifer aquifer = system.createAquifer(settings, key.x(), key.y(), key.z(), router, null, picker);
        if (aquifer == null) {
            aquifer = Aquifers.disabled(picker);
        }
        BlockState fallback = system.defaultBlock(settings);
        LongBox box = LongBox.ofSection(key.x(), key.y(), key.z());
        SectionSink sink = new SectionSink(data.section, aquifer, fallback);
        int cellWidth = settings.cellWidth();
        int cellHeight = settings.cellHeight();
        if (density instanceof InterpolatedDensity && CellSampler.supports(cellWidth, cellHeight)) {
            CellSampler.forEach(density, box.minX(), box.minY(), box.minZ(), cellWidth, cellHeight, sink);
        } else {
            Sampler.forEach(density, box, sink);
        }
    }

    /** 世界级原型，段范围每段换出。方块与液位是这一层自己的口径，与维度设置无关，因此每次现建不缓存。 */
    private static Settings prototypeFor(SimulationNoiseSystem system) {
        return new Settings(0L, 16L, resolveSize(system.noiseSizeXZ()), resolveSize(system.noiseSizeY()),
                Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), BASE_ROUTER, 63L, true, false);
    }

    /** 0 表示用原型那一份。非 0 时它是 cell 网格的分量，cell 边长等于它乘 4。 */
    private static int resolveSize(int declared) {
        return declared != 0 ? declared : 1;
    }

    /** 一格的落点。段内下标取低四位，与 {@code AbstractTerrainFiller} 同式。 */
    private static final class SectionSink implements Sink {

        private final LevelChunkSection section;

        private final Aquifer aquifer;

        private final BlockState fallback;

        SectionSink(LevelChunkSection section, Aquifer aquifer, BlockState fallback) {
            this.section = section;
            this.aquifer = aquifer;
            this.fallback = fallback;
        }

        @Override
        public void accept(long x, long y, long z, double value) {
            BlockState state = this.aquifer.computeSubstance(x, y, z, value);
            if (state == null) {
                state = this.fallback;
            }
            if (state.isAir()) {
                return;
            }
            this.section.setBlockState((int) (x & 15), (int) (y & 15), (int) (z & 15), state, false);
        }
    }
}
