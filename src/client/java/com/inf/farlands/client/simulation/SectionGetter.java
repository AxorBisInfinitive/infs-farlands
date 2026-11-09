package com.inf.farlands.client.simulation;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 段上的取数入口：一个可复用的 {@link BlockAndTintGetter}，把位置当作段内局部坐标。
 *
 * <p>
 * 不读世界坐标：模拟之地的坐标在 {@code BlockPos} 上已饱和。局部坐标落在 0 到 15，越出本段的采样点按算术右移求格、
 * {@code & 15} 折回段内，再查邻段。缺邻段按空气、光 0、群系取默认值。
 *
 * <p>
 * 两种模式。**实时模式**由碰撞与拾取使用，跑在客户端主线程上，邻段随时从 {@code Simulation} 查，方块色表与混合半径
 * 随时从 {@code Minecraft} 取。**快照模式**由烘焙使用，跑在工作线程上，邻域与那几样客户端物件全部来自
 * {@link BakeSnapshot}，本类因此一个 {@code Minecraft} 单例都不碰。
 *
 * <p>
 * 光走 {@link #getBrightness}，它必须覆写：接口默认实现读 {@code getLightEngine()}，而这里返回空引擎，不覆写会让整层
 * 的光恒为 0 且不报错。
 */
public final class SectionGetter implements BlockAndTintGetter {

    private final SimulationSection section;
    private final Simulation simulation;
    private final BakeSnapshot snapshot;
    private final SimulationSection[] neighbors;
    private final BlockPos.MutableBlockPos unused = new BlockPos.MutableBlockPos();

    private int localX;
    private int localY;
    private int localZ;
    private BlockState state = Blocks.AIR.defaultBlockState();

    /** 当前块的 tint 色，按层索引。复用同一个数组，避免每块分配。 */
    private int[] colors = new int[4];
    private int colorCount;

    /** 实时模式：主线程，邻段与客户端物件按需取。 */
    public SectionGetter(SimulationSection section, Simulation simulation) {
        this.section = section;
        this.simulation = simulation;
        this.snapshot = null;
        this.neighbors = null;
    }

    /** 快照模式：工作线程，全部输入由快照定住。 */
    SectionGetter(BakeSnapshot snapshot) {
        this.section = snapshot.section();
        this.simulation = null;
        this.snapshot = snapshot;
        this.neighbors = snapshot.neighbors();
    }

    /** 切到该块并算好它的 tint 色。每个块一次，同一块的多个 quad 复用。 */
    void at(int x, int y, int z, BlockState blockState) {
        this.localX = x;
        this.localY = y;
        this.localZ = z;
        this.state = blockState;
        List<BlockTintSource> sources = this.blockColors().getTintSources(blockState);
        if (this.colors.length < sources.size()) {
            this.colors = new int[sources.size()];
        }
        this.colorCount = sources.size();
        for (int i = 0; i < sources.size(); i++) {
            this.colors[i] = sources.get(i).colorInWorld(blockState, this, this.unused);
        }
    }

    BlockState state() {
        return this.state;
    }

    /** 该 tint 层的颜色。层不存在时为 -1，即不改变顶点色。 */
    int tintColor(int tintIndex) {
        return tintIndex < this.colorCount ? this.colors[tintIndex] : -1;
    }

    /**
     * 段内局部坐标解析到段；同段返回本段，缺段返回 null。
     *
     * <p>
     * 格号用算术右移取，与 {@code Math.floorDiv} 在 int 上同值；格内坐标用 {@code & 15} 取。快照模式只备了 27 格，
     * 落在其外的采样按缺段处理。
     */
    private SimulationSection sectionFor(int sampleX, int sampleY, int sampleZ) {
        int cellX = sampleX >> 4;
        int cellY = sampleY >> 4;
        int cellZ = sampleZ >> 4;
        if (cellX == 0 && cellY == 0 && cellZ == 0) {
            return this.section;
        }
        if (this.neighbors == null) {
            return this.simulation.sectionOrNull(this.section.sectionX() + cellX, this.section.sectionY() + cellY,
                    this.section.sectionZ() + cellZ);
        }
        if (cellX < -1 || cellX > 1 || cellY < -1 || cellY > 1 || cellZ < -1 || cellZ > 1) {
            return null;
        }
        return this.neighbors[(cellX + 1) * 9 + (cellY + 1) * 3 + (cellZ + 1)];
    }

    /** 局部坐标折回 0 到 15。 */
    private static int fold(int localCoord) {
        return localCoord & 15;
    }

    private net.minecraft.client.color.block.BlockColors blockColors() {
        return this.snapshot != null ? this.snapshot.blockColors() : Minecraft.getInstance().getBlockColors();
    }

    private Holder<Biome> defaultBiome() {
        return this.snapshot != null ? this.snapshot.defaultBiome() : this.simulation.defaultBiome();
    }

    private int biomeBlendRadius() {
        return this.snapshot != null ? this.snapshot.biomeBlendRadius()
                : Minecraft.getInstance().options.biomeBlendRadius().get();
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return this.snapshot != null ? this.snapshot.cardinalLighting() : this.simulation.cardinalLighting();
    }

    /**
     * 该格的块色。先查段上的缓存，未算才做混合取样。
     *
     * <p>
     * 缓存键是 resolver、Y 的 quart 带与格内水平坐标；混合取样把 Y 固定在方块自己的 Y 上，而群系按四立方 quart
     * 取值，所以同一带内各 Y 同值。一次命中是一次数组读，未命中是 (2r+1)² 次群系查询。
     */
    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        int cached = this.section.cachedTint(resolver, this.localX, this.localY, this.localZ);
        if (cached != 0) {
            return cached;
        }
        int color = this.farlands$computeTint(resolver);
        this.section.putTint(resolver, this.localX, this.localY, this.localZ, color);
        return color;
    }

    /** 混合取样，与 {@code ClientLevel.calculateBlockTint} 同式。 */
    private int farlands$computeTint(ColorResolver resolver) {
        int dist = this.biomeBlendRadius();
        if (dist == 0) {
            return resolver.getColor(biomeAt(this.localX, this.localZ).value(),
                    this.section.originX() + this.localX, this.section.originZ() + this.localZ);
        }
        int count = (dist * 2 + 1) * (dist * 2 + 1);
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int dx = -dist; dx <= dist; dx++) {
            for (int dz = -dist; dz <= dist; dz++) {
                int sampleX = this.localX + dx;
                int sampleZ = this.localZ + dz;
                int color = resolver.getColor(biomeAt(sampleX, sampleZ).value(),
                        this.section.originX() + sampleX, this.section.originZ() + sampleZ);
                red += ARGB.red(color);
                green += ARGB.green(color);
                blue += ARGB.blue(color);
            }
        }
        return ARGB.color(red / count, green / count, blue / count);
    }

    /**
     * 样本点的群系。样本跨出本段时查邻段，邻段不在快照里回落到默认群系。
     *
     * <p>
     * 半径最大 7，段边长 16，所以最多跨一列；折回段内后的局部坐标仍在 0 到 15。
     */
    private Holder<Biome> biomeAt(int sampleX, int sampleZ) {
        SimulationSection target = sectionFor(sampleX, this.localY, sampleZ);
        if (target == null) {
            return this.defaultBiome();
        }
        return target.getNoiseBiome(fold(sampleX) >> 2, this.localY >> 2, fold(sampleZ) >> 2);
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        SimulationSection target = sectionFor(pos.getX(), pos.getY(), pos.getZ());
        if (target == null) {
            return 0;
        }
        return target.getLight(layer, fold(pos.getX()), fold(pos.getY()), fold(pos.getZ()));
    }

    /** 按段内局部坐标取块状态。缺段与空格按空气。 */
    @Override
    public BlockState getBlockState(BlockPos pos) {
        SimulationSection target = sectionFor(pos.getX(), pos.getY(), pos.getZ());
        if (target == null) {
            return Blocks.AIR.defaultBlockState();
        }
        SimulationBlock block = target.blockAt(fold(pos.getX()), fold(pos.getY()), fold(pos.getZ()));
        return block == null ? Blocks.AIR.defaultBlockState() : block.state();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public int getHeight() {
        return SimulationSection.SIZE;
    }

    @Override
    public int getMinY() {
        return 0;
    }
}
