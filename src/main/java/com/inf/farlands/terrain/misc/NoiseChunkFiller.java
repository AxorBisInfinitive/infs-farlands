package com.inf.farlands.terrain.misc;

import com.inf.farlands.mixin.noise.NoiseChunkInvoker;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;

import java.util.function.Supplier;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * NoiseChunk 逐格填充逻辑。
 *
 * fabric 尚无完整 terrain 管线，本类为静态方法形态；未来演进为抽象 filler。
 */
public final class NoiseChunkFiller {

    private NoiseChunkFiller() {
    }

    /**
     * 窗口滑入 section 的按需填充。
     *
     * <p>本方法当前不可达，生成金字塔被 GenerationChunkHolderMixin 短路，保留它是为复活时用。
     * 复活时必须走 {@link ChunkBeardifier#getBeardifier()} 这一支；同文件的
     * {@code doFillRange} 收的是形参 {@code structureManager}，那是 vanilla 生成金字塔的入口，
     * 与我们的短路互斥，不要照抄。
     */
    public static void fillWindowSections(ServerLevel level, ChunkAccess chunk, int minSection, int maxSection,
            NoiseGeneratorSettings settings, Supplier<Aquifer.FluidPicker> globalFluidPicker) {
        NoiseSettings orig = settings.noiseSettings();
        int noiseH = orig.noiseSizeHorizontal();
        int noiseV = orig.noiseSizeVertical();
        int cellW = QuartPos.toBlock(noiseH);
        int cellH = QuartPos.toBlock(noiseV);

        int minBlockY = minSection * 16;
        int maxBlockY = (maxSection + 1) * 16 - 1;
        int height = maxBlockY - minBlockY + 1;
        if (height % 16 != 0)
            height = ((height + 15) / 16) * 16;

        NoiseSettings customNS = new NoiseSettings(minBlockY, height, noiseH, noiseV);

        RandomState randomState = level.getChunkSource().randomState();
        // 与 AbstractTerrainFiller 同规：Beardifier 必须从 chunk 读，不在这里现算。现算会经
        // StructureManager 回到 ServerChunkCache 取 chunk，生成线程上即阻塞等主线程。
        Beardifier beardifier = ((ChunkBeardifier) chunk).getBeardifier();

        int cellCountXZ = 16 / cellW;
        // NoiseChunk 构造会走 NoiseChunkMixin 的两处 @Redirect，构造点必须先 set level。
        // 本类当前不可达，生成金字塔被 GenerationChunkHolderMixin 短路，此 set 是为复活时留的正确入口。
        TerrainSystemContext.set(level);
        NoiseChunk nc;
        try {
            nc = new NoiseChunk(
                    cellCountXZ, randomState,
                    chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                    customNS, beardifier, settings,
                    globalFluidPicker.get(), Blender.empty());
        } finally {
            TerrainSystemContext.clear();
        }

        int minCellY = Mth.floorDiv(customNS.minY(), cellH);
        int cellCountY = Mth.floorDiv(customNS.height(), cellH);

        doFillRangeWithNoiseChunk(nc, chunk, settings, minSection, maxSection, minCellY, cellCountY);
    }

    /** 初始全量填充。 */
    public static ChunkAccess doFillRange(Blender blender, StructureManager structureManager, RandomState random,
            ChunkAccess chunk, int minSection, int maxSection,
            NoiseGeneratorSettings settings, Supplier<Aquifer.FluidPicker> globalFluidPicker) {
        NoiseChunk nc = chunk.getOrCreateNoiseChunk(p -> NoiseChunk.forChunk(
                p, random,
                Beardifier.forStructuresInChunk(structureManager, p.getPos()),
                settings,
                globalFluidPicker.get(),
                blender));
        NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int cellH = ns.getCellHeight();
        int minCellY = Mth.floorDiv(ns.minY(), cellH);
        int cellCountY = Mth.floorDiv(ns.height(), cellH);
        return doFillRangeWithNoiseChunk(nc, chunk, settings, minSection, maxSection, minCellY, cellCountY);
    }

    private static ChunkAccess doFillRangeWithNoiseChunk(NoiseChunk noisechunk, ChunkAccess chunk,
            NoiseGeneratorSettings settings,
            int minSection, int maxSection, int minCellY, int cellCountY) {
        Heightmap hmOcean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap hmSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos cpos = chunk.getPos();
        int baseX = cpos.getMinBlockX();
        int baseZ = cpos.getMinBlockZ();
        NoiseChunkInvoker inv = (NoiseChunkInvoker) noisechunk;
        Aquifer aquifer = inv.farlands$aquifer();
        inv.farlands$initializeForFirstCellX();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        int cw = inv.farlands$cellWidth();
        int ch = inv.farlands$cellHeight();
        int cellsX = 16 / cw;
        int cellsZ = 16 / cw;

        int totalSections = chunk.getSectionsCount();
        for (int i = 0; i < totalSections; i++) {
            chunk.getSection(i);
        }

        for (int cx = 0; cx < cellsX; cx++) {
            inv.farlands$advanceCellX(cx);
            for (int cz = 0; cz < cellsZ; cz++) {
                fillCellColumn(noisechunk, inv, chunk, settings, minSection, maxSection, minCellY, cellCountY,
                        ch, cw, cx, cz, baseX, baseZ, hmOcean, hmSurface, aquifer, mpos, cpos);
            }
        }
        return chunk;
    }

    private static void fillCellColumn(
            NoiseChunk noisechunk,
            NoiseChunkInvoker inv,
            ChunkAccess chunk,
            NoiseGeneratorSettings settings,
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
            ChunkPos cpos) {
        int cyStart = Math.max(0, minSection * 16 / ch - minCellY);
        int cyEnd = Math.min(cellCountY - 1, (maxSection * 16 + 15) / ch - minCellY);
        if (cyStart > cyEnd) {
            return;
        }

        int prevSecIdx = chunk.getSectionsCount() - 1;
        LevelChunkSection section = chunk.getSection(prevSecIdx);
        for (int cy = cyEnd; cy >= cyStart; cy--) {
            inv.farlands$selectCellYZ(cy, cz);
            for (int ky = ch - 1; ky >= 0; ky--) {
                int blockY = (minCellY + cy) * ch + ky;
                int ry = blockY & 15;
                int secIdx = chunk.getSectionIndex(blockY);
                if (prevSecIdx != secIdx) {
                    prevSecIdx = secIdx;
                    section = chunk.getSection(secIdx);
                }
                inv.farlands$updateForY(blockY, (double) ky / (double) ch);
                for (int kx = 0; kx < cw; kx++) {
                    int blockX = baseX + cx * cw + kx;
                    int rx = blockX & 15;
                    inv.farlands$updateForX(blockX, (double) kx / (double) cw);
                    for (int kz = 0; kz < cw; kz++) {
                        int blockZ = baseZ + cz * cw + kz;
                        int rz = blockZ & 15;
                        inv.farlands$updateForZ(blockZ, (double) kz / (double) cw);
                        BlockState bs = inv.farlands$getInterpolatedState();
                        if (bs == null) {
                            bs = settings.defaultBlock();
                        }
                        bs = applyDebugPreliminarySurfaceLevel(noisechunk, settings.seaLevel(),
                                blockX, blockY, blockZ, bs);
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

    private static BlockState applyDebugPreliminarySurfaceLevel(NoiseChunk noiseChunk, int seaLevel,
            int x, int y, int z, BlockState state) {
        if (SharedConstants.DEBUG_AQUIFERS && z >= 0 && z % 4 == 0) {
            int preliminarySurfaceLevel = noiseChunk.preliminarySurfaceLevel(x, z);
            int adjustedSurfaceLevel = preliminarySurfaceLevel + 8;
            if (y == adjustedSurfaceLevel) {
                state = adjustedSurfaceLevel < seaLevel
                        ? Blocks.SLIME_BLOCK.defaultBlockState()
                        : Blocks.HONEY_BLOCK.defaultBlockState();
            }
        }
        return state;
    }
}
