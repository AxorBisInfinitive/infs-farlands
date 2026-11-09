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
 * 随时从 {@code Minecraft} 取。**快照模式**由烘焙使用，跑在工作线程上，数据来自 {@link BakeSnapshot} 带进来的已发布
 * 内容，邻域也是内容而不是活段，本类因此不读任何会被主线程改写的东西，也不碰 {@code Minecraft} 单例。
 *
 * <p>
 * 光走 {@link #getBrightness}，它必须覆写：接口默认实现读 {@code getLightEngine()}，而这里返回空引擎，不覆写会让整层
 * 的光恒为 0 且不报错。
 */
public final class SectionGetter implements BlockAndTintGetter {

    /** 实时模式下的活段；快照模式为 null。 */
    private final SimulationSection section;
    private final Simulation simulation;

    /** tint 缓存挂在段上，快照模式借快照带的宿主段；只调它的 tint 那两个方法。 */
    private final SimulationSection tintOwner;

    /** 快照模式下的本段内容与 27 格邻域内容；实时模式为 null。 */
    private final SimulationSection.Content content;
    private final SimulationSection.Content[] neighborContents;

    /** 段坐标。快照模式下活段不存在，色温采样要的段原点从这里算。 */
    private final long sectionX;
    private final long sectionY;
    private final long sectionZ;

    private final BakeSnapshot snapshot;
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
        this.tintOwner = section;
        this.snapshot = null;
        this.content = null;
        this.neighborContents = null;
        this.sectionX = section.sectionX();
        this.sectionY = section.sectionY();
        this.sectionZ = section.sectionZ();
    }

    /** 快照模式：工作线程，全部输入由快照定住。 */
    SectionGetter(BakeSnapshot snapshot) {
        this.section = null;
        this.simulation = null;
        this.tintOwner = snapshot.owner();
        this.snapshot = snapshot;
        this.content = snapshot.content();
        this.neighborContents = snapshot.neighbors();
        this.sectionX = snapshot.key().x();
        this.sectionY = snapshot.key().y();
        this.sectionZ = snapshot.key().z();
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

    /** 格号是否就是本段。 */
    private static boolean sameCell(int cellX, int cellY, int cellZ) {
        return cellX == 0 && cellY == 0 && cellZ == 0;
    }

    /** 27 格里是否备了这一格。 */
    private static boolean inNeighborhood(int cellX, int cellY, int cellZ) {
        return cellX >= -1 && cellX <= 1 && cellY >= -1 && cellY <= 1 && cellZ >= -1 && cellZ <= 1;
    }

    /** 快照模式下该格所属的内容；缺邻段返回 null。 */
    private SimulationSection.Content contentAt(int cellX, int cellY, int cellZ) {
        if (sameCell(cellX, cellY, cellZ)) {
            return this.content;
        }
        if (!inNeighborhood(cellX, cellY, cellZ)) {
            return null;
        }
        return this.neighborContents[(cellX + 1) * 9 + (cellY + 1) * 3 + (cellZ + 1)];
    }

    /** 实时模式下该格所属的活段；缺邻段返回 null。 */
    private SimulationSection sectionAt(int cellX, int cellY, int cellZ) {
        if (sameCell(cellX, cellY, cellZ)) {
            return this.section;
        }
        return this.simulation.sectionOrNull(this.sectionX + cellX, this.sectionY + cellY,
                this.sectionZ + cellZ);
    }

    /** 局部坐标折回 0 到 15。 */
    private static int fold(int localCoord) {
        return localCoord & 15;
    }

    private long originX() {
        return this.sectionX * SimulationSection.SIZE;
    }

    private long originZ() {
        return this.sectionZ * SimulationSection.SIZE;
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
     *
     * <p>
     * 缓存挂在段上而不是内容上：它按格缓存，与具体哪一代内容无关，清空由主线程在群系变化时统一做。
     */
    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        SimulationSection owner = this.tintOwner;
        int cached = owner.cachedTint(resolver, this.localX, this.localY, this.localZ);
        if (cached != 0) {
            return cached;
        }
        int color = this.farlands$computeTint(resolver);
        owner.putTint(resolver, this.localX, this.localY, this.localZ, color);
        return color;
    }

    /** 混合取样，与 {@code ClientLevel.calculateBlockTint} 同式。 */
    private int farlands$computeTint(ColorResolver resolver) {
        int dist = this.biomeBlendRadius();
        if (dist == 0) {
            return resolver.getColor(biomeAt(this.localX, this.localZ).value(),
                    originX() + this.localX, originZ() + this.localZ);
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
                        originX() + sampleX, originZ() + sampleZ);
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
        int cellX = sampleX >> 4;
        int cellY = this.localY >> 4;
        int cellZ = sampleZ >> 4;
        if (this.content != null) {
            SimulationSection.Content target = contentAt(cellX, cellY, cellZ);
            if (target == null) {
                return this.defaultBiome();
            }
            return target.biomes().get(fold(sampleX) >> 2, this.localY >> 2, fold(sampleZ) >> 2);
        }
        SimulationSection target = sectionAt(cellX, cellY, cellZ);
        if (target == null) {
            return this.defaultBiome();
        }
        return target.getNoiseBiome(fold(sampleX) >> 2, this.localY >> 2, fold(sampleZ) >> 2);
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        int sx = pos.getX();
        int sy = pos.getY();
        int sz = pos.getZ();
        if (this.content != null) {
            SimulationSection.Content target = contentAt(sx >> 4, sy >> 4, sz >> 4);
            if (target == null) {
                return 0;
            }
            return target.light(layer, fold(sx), fold(sy), fold(sz));
        }
        SimulationSection target = sectionAt(sx >> 4, sy >> 4, sz >> 4);
        if (target == null) {
            return 0;
        }
        return target.getLight(layer, fold(sx), fold(sy), fold(sz));
    }

    /** 按段内局部坐标取块状态。缺段与空格按空气。 */
    @Override
    public BlockState getBlockState(BlockPos pos) {
        int sx = pos.getX();
        int sy = pos.getY();
        int sz = pos.getZ();
        if (this.content != null) {
            SimulationSection.Content target = contentAt(sx >> 4, sy >> 4, sz >> 4);
            if (target == null) {
                return Blocks.AIR.defaultBlockState();
            }
            SimulationBlock block = target.blockAt(fold(sx) << 8 | fold(sy) << 4 | fold(sz));
            return block == null ? Blocks.AIR.defaultBlockState() : block.state();
        }
        SimulationSection target = sectionAt(sx >> 4, sy >> 4, sz >> 4);
        if (target == null) {
            return Blocks.AIR.defaultBlockState();
        }
        SimulationBlock block = target.blockAt(fold(sx), fold(sy), fold(sz));
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
