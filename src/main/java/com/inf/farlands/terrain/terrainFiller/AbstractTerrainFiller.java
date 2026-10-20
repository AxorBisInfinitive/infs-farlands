package com.inf.farlands.terrain.terrainFiller;

import com.inf.farlands.mixin.noise.NoiseChunkInvoker;
import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.TerrainSystem;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * 维度地形填充器抽象基类，按维度填充一段 section。
 *
 * 与原版 NoiseBasedChunkGenerator.doFill 的差异：
 *   Y 循环裁剪到 [minSection, maxSection]，即按需生成的窗口段，不是全量 cellCountY
 *   每个 X cell 处理完后 swapSlices，全部结束后 stopInterpolation。vanilla 链带 Interpolated
 *   标记的函数依赖 slice 双缓冲推进，缺失会让第 2 个 X cell 起 X 方向错位。纯函数系统不用
 *   slice，无影响。
 *
 * 并发约束：fillCellColumn 更新 chunk 共享的高度图，BitStorage 非线程安全，同 chunk 的 fill
 * 必须串行，由调用方保证，不同 chunk 并行。
 *
 * <p>维度系统与 generatorSettings 都从 fill 的 level 形参现取：填充器实例挂在 level 上，
 * 拿它自己的 ServerLevel 就是该维度的，因此构造器不需要参数。
 */
public abstract class AbstractTerrainFiller implements TerrainFiller {

    /** 填充器实例由 ServerLevel 持有，空参构造是将来按 level 传参的落点。 */
    protected AbstractTerrainFiller() {
    }

    /** 维度 biome 查询侧信道设置。DensityFunction.compute 没有 level 引用，需要维度
     * 上下文的系统由子类覆写本钩子。默认空，当前无子类覆写。 */
    protected void setContext(RandomState randomState, ServerLevel level) {
    }

    /** 与 {@link #setContext} 对称的清理，fill 出口的 finally 调用。 */
    protected void clearContext() {
    }

    private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : water;
    }

    /**
     * 构建维度范围 NoiseChunk，用全高 settings，即 SURFACE 阶段的入口。
     *
     * <p>自己收口 TerrainSystemContext：构造 NoiseChunk 时两处 @Redirect 要按当前系统替换
     * finalDensity 与 aquifer，而构造器里没有 level，只能靠这个侧信道。维度从 level 推导，
     * 调用方不必知道维度。
     */
    public static NoiseChunk createDimensionNoiseChunk(ServerLevel level, ChunkAccess chunk) {
        // 只看噪声生成器的维度：本方法的两个调用方 VanillaSurfaceSystem 与 VanillaCarverSystem 都在
        // 入口处先判了生成器类型，非噪声维度根本走不到这里。裸转留在这里，判据由调用方持有，因为
        // 它们手上的生成器类型判据同时也决定「这一整套是否该跑」。
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        TerrainSystemContext.set(((LevelSystems) level).terrainSystem());
        try {
            NoiseGeneratorSettings genSettings = gen.generatorSettings().value();
            NoiseSettings orig = genSettings.noiseSettings();
            RandomState randomState = level.getChunkSource().randomState();
            Beardifier beardifier = ((ChunkBeardifier) chunk).getBeardifier();
            int cellW = QuartPos.toBlock(orig.noiseSizeHorizontal());
            int cellCountXZ = 16 / cellW;
            return new NoiseChunk(
                    cellCountXZ, randomState,
                    chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                    orig, beardifier, genSettings,
                    createFluidPicker(genSettings), Blender.empty());
        } finally {
            TerrainSystemContext.clear();
        }
    }

    /** 填一段 section 的地形，1 段对应 1 个 NoiseChunk。调用方保证同 chunk 串行。 */
    @Override
    public void fill(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY) {
        // 非噪声生成器的维度不归本管线。维度泛化之后任何数据包或模组都能加维度，生成器类型不再只有
        // NoiseBasedChunkGenerator。硬转会在 fill 里抛，而 GenTask 的 catch 只记录再重抛，紧随其后的
        // setStage(TERRAIN) 因此永不执行，completeTask 反复重新入队，形成自持的错误环，也就是实测里
        // 那一千多万行 ClassCastException。这里直接返回：别人生成的维度交给别人，本管线不碰。
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen)) {
            return;
        }
        TerrainSystem sys = ((LevelSystems) level).terrainSystem();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        Aquifer.FluidPicker defaultPicker = createFluidPicker(settings);
        RandomState randomState = level.getChunkSource().randomState();
        TerrainSystemContext.set(sys);
        setContext(randomState, level);
        try {
            NoiseSettings orig = settings.noiseSettings();
            // 网格分派：NoiseSystem 可自定义 noiseSize，null 用维度 settings 默认。
            int[] ns = null;
            if (sys instanceof NoiseSystem n) {
                ns = n.noiseSize();
            }
            int noiseH = ns != null ? ns[0] : orig.noiseSizeHorizontal();
            int noiseV = ns != null ? ns[1] : orig.noiseSizeVertical();
            int cellW = QuartPos.toBlock(noiseH);
            int cellH = QuartPos.toBlock(noiseV);

            // 恰好覆盖该段 section 的 NoiseSettings，避免 NoiseSettings.create 的 MAX_Y 校验。
            NoiseSettings customNS = new NoiseSettings(minSectionY * 16, (maxSectionY - minSectionY + 1) * 16, noiseH, noiseV);

            Beardifier beardifier = ((ChunkBeardifier) chunk).getBeardifier();

            int cellCountXZ = 16 / cellW;
            // fluidPicker 按维度系统取，null 用本类默认。
            Aquifer.FluidPicker picker = sys.createFluidPicker(settings);
            if (picker == null) {
                picker = defaultPicker;
            }
            NoiseChunk nc = new NoiseChunk(
                    cellCountXZ, randomState,
                    chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                    customNS, beardifier, settings,
                    picker, Blender.empty());

            int minCellY = Mth.floorDiv(customNS.minY(), cellH);
            int cellCountY = Mth.floorDiv(customNS.height(), cellH);

            doFillRangeWithNoiseChunk(nc, chunk, minSectionY, maxSectionY, minCellY, cellCountY, sys, settings);
        } finally {
            clearContext();
            TerrainSystemContext.clear();
        }
    }

    /** 填充循环，对齐原版 NoiseBasedChunkGenerator.doFill。sys 与 settings 由 fill 现取后透传。 */
    protected void doFillRangeWithNoiseChunk(NoiseChunk noisechunk, ChunkAccess chunk,
            int minSection, int maxSection, int minCellY, int cellCountY,
            TerrainSystem sys, NoiseGeneratorSettings settings) {
        Heightmap hmOcean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap hmSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos cpos = chunk.getPos();
        int baseX = cpos.getMinBlockX();
        int baseZ = cpos.getMinBlockZ();
        Aquifer aquifer = noisechunk.aquifer();
        noisechunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        NoiseChunkInvoker inv = (NoiseChunkInvoker) noisechunk;
        int cw = inv.farlands$cellWidth();
        int ch = inv.farlands$cellHeight();
        int cellsX = 16 / cw;
        int cellsZ = 16 / cw;

        for (int cx = 0; cx < cellsX; cx++) {
            noisechunk.advanceCellX(cx);
            for (int cz = 0; cz < cellsZ; cz++) {
                fillCellColumn(noisechunk, inv, chunk, minSection, maxSection, minCellY, cellCountY,
                        ch, cw, cx, cz, baseX, baseZ, hmOcean, hmSurface, aquifer, mpos, cpos, sys, settings);
            }
            noisechunk.swapSlices();
        }
        noisechunk.stopInterpolation();
    }

    private void fillCellColumn(
            NoiseChunk noisechunk,
            NoiseChunkInvoker inv,
            ChunkAccess chunk,
            int minSection,
            int maxSection,
            int minCellY,
            int cellCountY,
            int ch,
            int cw,
            int cx,
            int cz,
            int baseX,
            int baseZ,
            Heightmap hmOcean,
            Heightmap hmSurface,
            Aquifer aquifer,
            BlockPos.MutableBlockPos mpos,
            ChunkPos cpos,
            TerrainSystem sys,
            NoiseGeneratorSettings settings) {
        int cyStart = Math.max(0, minSection * 16 / ch - minCellY);
        int cyEnd = Math.min(cellCountY - 1, (maxSection * 16 + 15) / ch - minCellY);
        if (cyStart > cyEnd) {
            return;
        }

        int prevSecIdx = chunk.getSectionsCount() - 1;
        LevelChunkSection section = chunk.getSection(prevSecIdx);
        for (int cy = cyEnd; cy >= cyStart; cy--) {
            noisechunk.selectCellYZ(cy, cz);
            for (int ky = ch - 1; ky >= 0; ky--) {
                int blockY = (minCellY + cy) * ch + ky;
                int ry = blockY & 15;
                int secIdx = chunk.getSectionIndex(blockY);
                if (prevSecIdx != secIdx) {
                    prevSecIdx = secIdx;
                    section = chunk.getSection(secIdx);
                }
                noisechunk.updateForY(blockY, (double) ky / (double) ch);
                for (int kx = 0; kx < cw; kx++) {
                    int blockX = baseX + cx * cw + kx;
                    int rx = blockX & 15;
                    noisechunk.updateForX(blockX, (double) kx / (double) cw);
                    for (int kz = 0; kz < cw; kz++) {
                        int blockZ = baseZ + cz * cw + kz;
                        int rz = blockZ & 15;
                        noisechunk.updateForZ(blockZ, (double) kz / (double) cw);
                        // 块级填充优先，否则走密度链。
                        BlockState bs = sys instanceof BlockSystem b ? b.fillBlock(blockX, blockY, blockZ) : null;
                        if (bs == null) {
                            bs = inv.farlands$getInterpolatedState();
                            if (bs == null) {
                                // 落点按系统口径取，不直读维度设置：系统换到别的维度时要自带方块。
                                bs = sys.defaultBlock(settings);
                            }
                        }
                        if (bs != Blocks.AIR.defaultBlockState() && !SharedConstants.debugVoidTerrain(cpos)) {
                            section.setBlockState(rx, ry, rz, bs, false);
                            hmOcean.update(rx, blockY, rz, bs);
                            hmSurface.update(rx, blockY, rz, bs);
                            if (aquifer.shouldScheduleFluidUpdate() && !bs.getFluidState().isEmpty()) {
                                mpos.set(blockX, blockY, blockZ);
                                if (chunk instanceof ProtoChunk) {
                                    chunk.markPosForPostprocessing(mpos);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
