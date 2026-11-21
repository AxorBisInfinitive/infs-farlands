package com.inf.farlands.simulation.int1024.terrainFiller;

import java.util.Objects;

import com.inf.farlands.simulation.SimulationLevels;
import com.inf.farlands.simulation.int1024.SimulationBlockSystem;
import com.inf.farlands.simulation.int1024.SimulationNoiseSystem;
import com.inf.farlands.simulation.int1024.SimulationRouting;
import com.inf.farlands.simulation.int1024.SimulationSectionContext;
import com.inf.farlands.simulation.int1024.SimulationSectionSystem;
import com.inf.farlands.simulation.int1024.SimulationTerrainSystem;
import com.inf.farlands.simulation.int1024.noise.Aquifer;
import com.inf.farlands.simulation.int1024.noise.CellSampler;
import com.inf.farlands.simulation.int1024.noise.Density;
import com.inf.farlands.simulation.int1024.noise.FluidPicker;
import com.inf.farlands.simulation.int1024.noise.InterpolatedDensity;
import com.inf.farlands.simulation.int1024.noise.Router;
import com.inf.farlands.simulation.int1024.noise.Sampler;
import com.inf.farlands.simulation.int1024.noise.Settings;
import com.inf.farlands.simulation.int1024.noise.instance.Aquifers;
import com.inf.farlands.simulation.int1024.noise.instance.FluidPickers;
import com.inf.farlands.simulation.int1024.util.SectionBox;
import com.inf.farlands.simulation.int1024.util.Sink;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import love.crysh.long2.ints.Int1024;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * 段填充的全部逻辑，与 terrain 的 {@code AbstractTerrainFiller} 同形。
 *
 * <p>一次填充五步：按段换出 {@code Settings}，取系统给的路由，取液面查询与含水层并在返回 null 时落到默认件，
 * 遍历求值，最后由材质规则定方块。材质规则照抄 vanilla：含水层返回 {@code null} 表示实方块，取
 * {@code defaultBlock}；空气不写。
 *
 * <p>只写方块。群系在它自己那一档，由 {@code SimulationBiomeFiller} 写，两者只共用 {@code SimulationRouting}
 * 给出的同一份路由。
 *
 * <p>遍历有两条路径，由密度自己声明：实现 {@link InterpolatedDensity} 的走 {@link CellSampler}，
 * 先按 cell 网格算角点、再块内三线性插值；其余走 {@link Sampler} 的逐块求值。两条路径喂同一个
 * {@code SectionSink}，写入面完全相同。cell 路径要求 cell 边长整除一段，不成立时退回逐块。
 *
 * <p>系统按入参 {@code level} 取，本类不认识当前是哪一个。取数点只有一处，是
 * {@code SimulationLevels.simulationTerrainSystem()}：那边返回基接口，实例由该维度的落盘选择反射新建，与 farlands
 * 的 {@code SystemsHolder} 同一职责；密度链那一支收窄到噪声系统在 {@code SimulationRouting.noiseSystem}。
 *
 * <p>跑在 {@code farlands-sim} 上，所以段写入只用五参 {@code setBlockState} 的 {@code false} 一档，不碰阶段、
 * 不碰段表、不标脏，那三件由 {@code SimulationLifecycle} 在主线程做。
 */
public abstract class AbstractSimulationFiller implements SimulationFiller {

    /** 段内下标掩码，取低四位。 */
    private static final Int1024 LOCAL_MASK = Int1024.of(15L);

    protected AbstractSimulationFiller() {
    }

    @Override
    public void fill(ServerLevel level, SimulationSectionKey key, SimulationSectionData data,
            PalettedContainerRO<Holder<Biome>>[] biomes) {
        // 范式分派照 AbstractTerrainFiller：逐块最先认领，其次逐段，最后密度链。
        SimulationTerrainSystem base = ((SimulationLevels) level).simulationTerrainSystem();
        if (base instanceof SimulationBlockSystem blocks) {
            fillByBlocks(key, data, blocks);
            return;
        }
        if (base instanceof SimulationSectionSystem sections) {
            sections.fillSection(new SimulationSectionContext(level, key, data.section, key.x().shift(4),
                    key.y().shift(4), key.z().shift(4), biomes));
            return;
        }
        // 密度链那一支只用得上噪声系统：收窄点收在 SimulationRouting.noiseSystem 一处。
        SimulationNoiseSystem system = SimulationRouting.noiseSystem(level);
        Settings settings = prototypeFor(system).withRange(key.y().shift(4), SectionBox.SIZE);
        Router router = SimulationRouting.router(level);
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
        SectionBox box = SectionBox.ofSection(key.x(), key.y(), key.z());
        SectionSink sink = new SectionSink(data.section, aquifer, fallback);
        int cellWidth = settings.cellWidth();
        int cellHeight = settings.cellHeight();
        if (density instanceof InterpolatedDensity && CellSampler.supports(cellWidth, cellHeight)) {
            CellSampler.forEach(density, box, cellWidth, cellHeight, sink);
        } else {
            Sampler.forEach(density, box, sink);
        }
    }

    /**
     * 逐块范式的直写路径。
     *
     * <p>
     * 与密度链那条路差四样：不建 {@code NoiseChunk}，不取 {@code Settings} 与 {@code RandomState}，不碰含水层与
     * {@code Beardifier}，也没有高度图要喂，模拟段所在的 chunk 不存在。坐标取 {@link SectionBox} 预计算的三组，
     * 逐格不再做加法分配。
     *
     * <p>
     * 空气不写。返回 null 在这里直接抛，不退化成像空气。
     */
    private static void fillByBlocks(SimulationSectionKey key, SimulationSectionData data,
            SimulationBlockSystem system) {
        SectionBox box = SectionBox.ofSection(key.x(), key.y(), key.z());
        LevelChunkSection section = data.section;
        for (int localY = 0; localY < SectionBox.SIZE; localY++) {
            Int1024 y = box.y(localY);
            for (int localZ = 0; localZ < SectionBox.SIZE; localZ++) {
                Int1024 z = box.z(localZ);
                for (int localX = 0; localX < SectionBox.SIZE; localX++) {
                    BlockState state = Objects.requireNonNull(system.fillBlock(box.x(localX), y, z),
                            "farlands: SimulationBlockSystem.fillBlock returned null");
                    if (state.isAir()) {
                        continue;
                    }
                    section.setBlockState(localX, localY, localZ, state, false);
                }
            }
        }
    }

    /** 世界级原型，段范围每段换出。方块与液位是这一层自己的口径，与维度设置无关，因此每次现建不缓存。 */
    private static Settings prototypeFor(SimulationNoiseSystem system) {
        return new Settings(Int1024.ZERO, SectionBox.SIZE, resolveSize(system.noiseSizeXZ()),
                resolveSize(system.noiseSizeY()), Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), SimulationRouting.BASE, Int1024.of(63L), true, false);
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
        public void accept(Int1024 x, Int1024 y, Int1024 z, double value) {
            BlockState state = this.aquifer.computeSubstance(x, y, z, value);
            if (state == null) {
                state = this.fallback;
            }
            if (state.isAir()) {
                return;
            }
            this.section.setBlockState(x.and(LOCAL_MASK).toInt(), y.and(LOCAL_MASK).toInt(),
                    z.and(LOCAL_MASK).toInt(), state, false);
        }
    }
}
