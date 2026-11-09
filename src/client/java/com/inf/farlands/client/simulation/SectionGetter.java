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
 * 不读世界坐标：模拟之地的坐标在 {@code BlockPos} 上已饱和。局部坐标落在 0 到 15，越出本段的采样点按
 * {@code floorDiv} 求格、查邻段，折回段内后交给容器。缺邻段按空气、光 0、群系取该 level 的默认值。
 *
 * <p>
 * 三个消费者共用：烘几何时的剔除、光与群系采样，碰撞与拾取时的方块状态与形状派生。`at` 只服务需要「当前块」
 * 的那一侧，即 tint 颜色；形状派生只用 {@code getBlockState}，不依赖 `at`。
 *
 * <p>
 * 光走 {@link #getBrightness}，它必须覆写：接口默认实现读 {@code getLightEngine()}，而这里返回空引擎，不覆写会
 * 让整层的光恒为 0 且不报错。
 */
public final class SectionGetter implements BlockAndTintGetter {

    private final SimulationSection section;
    private final Simulation simulation;
    private final BlockPos.MutableBlockPos unused = new BlockPos.MutableBlockPos();

    private int localX;
    private int localY;
    private int localZ;
    private BlockState state = Blocks.AIR.defaultBlockState();

    /** 当前块的 tint 色，按层索引。复用同一个数组，避免每块分配。 */
    private int[] colors = new int[4];
    private int colorCount;

    public SectionGetter(SimulationSection section, Simulation simulation) {
        this.section = section;
        this.simulation = simulation;
    }

    /** 切到该块并算好它的 tint 色。每个块一次，同一块的多个 quad 复用。 */
    void at(int x, int y, int z, BlockState blockState) {
        this.localX = x;
        this.localY = y;
        this.localZ = z;
        this.state = blockState;
        List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(blockState);
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

    /** 段内局部坐标解析到段；同段直接返回本段，缺段返回 null。三轴同形。 */
    private SimulationSection sectionFor(int sampleX, int sampleY, int sampleZ) {
        int cellX = Math.floorDiv(sampleX, SimulationSection.SIZE);
        int cellY = Math.floorDiv(sampleY, SimulationSection.SIZE);
        int cellZ = Math.floorDiv(sampleZ, SimulationSection.SIZE);
        if (cellX == 0 && cellY == 0 && cellZ == 0) {
            return this.section;
        }
        return this.simulation.sectionOrNull(this.section.sectionX() + cellX, this.section.sectionY() + cellY,
                this.section.sectionZ() + cellZ);
    }

    /** 局部坐标折回 0 到 15。 */
    private static int fold(int localCoord) {
        return localCoord - Math.floorDiv(localCoord, SimulationSection.SIZE) * SimulationSection.SIZE;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return this.simulation.cardinalLighting();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        int dist = Minecraft.getInstance().options.biomeBlendRadius().get();
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
     * 样本点的群系。样本跨出本段时查邻段，邻段不在圆盘里回落到默认群系。
     *
     * <p>
     * 半径最大 7，段边长 16，所以最多跨一列；折回段内后的局部坐标仍在 0 到 15。
     */
    private Holder<Biome> biomeAt(int sampleX, int sampleZ) {
        SimulationSection target = sectionFor(sampleX, this.localY, sampleZ);
        if (target == null) {
            return this.simulation.defaultBiome();
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
