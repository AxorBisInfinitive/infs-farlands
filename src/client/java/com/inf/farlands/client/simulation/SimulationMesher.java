package com.inf.farlands.client.simulation;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.phys.AABB;

/**
 * 把一段模拟方块烘成段内局部的顶点数据，绑定一次、之后只上传。
 *
 * <p>
 * 顶点格式取 {@code DefaultVertexFormat.BLOCK}，与地形同一套，位置是段内 {@code 0} 到 {@code 16}。世界位置
 * 不进顶点，全部靠段 UBO 里的相机相对偏移，所以 int 之外的层不会在顶点这层丢精度。
 *
 * <p>
 * 面剔除只在段内做：某个方向上邻块非空就跳过那一方向的 quad。跨段边界一律可见，代价是每段边界多出几十个
 * quad，换来的是解绑与重绑时不必连带重算邻段。
 *
 * <p>
 * 着色走原版的两条路径：门成立时用 {@link BlockModelLighter#prepareQuadAmbientOcclusion}，否则用
 * {@link BlockModelLighter#prepareQuadFlat}。取数经自建的 {@link BlockAndTintGetter}，它把位置当作段内
 * 局部坐标，坐标与群系按 long 与段内 quart 取，越出本段时查邻段，缺段按空气、光 0、群系取该 level 的默认值。
 * tint 按原版口径相乘，方向明暗取该维度那份。
 *
 * <p>
 * 顶点按四边形四元一组顺序写出，与共享的顺序索引缓冲 {@code getSequentialBuffer(QUADS)} 的序列配套。
 */
public final class SimulationMesher {

    private static final Direction[] DIRECTIONS = Direction.values();

    /** 方块状态到模型部件表，取一次缓存。渲染线程独占。 */
    private static final Map<BlockState, List<BlockStateModelPart>> PARTS = new HashMap<>();

    private SimulationMesher() {
    }

    /** 一段的几何：顶点字节、顶点数、段内包围盒。空段返回 null。 */
    public record Mesh(byte[] vertices, int vertexCount, AABB bounds) {
    }

    /** 烘一段。空段返回 null。 */
    public static Mesh build(SimulationSection section, Simulation simulation) {
        boolean[] solid = new boolean[SimulationSection.SIZE * SimulationSection.SIZE * SimulationSection.SIZE];
        int occupied = section.occupiedCount();
        for (int i = 0; i < occupied; i++) {
            solid[section.occupiedIndex(i)] = true;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        int quads = 0;
        SectionGetter getter = new SectionGetter(section, simulation);
        BlockModelLighter lighter = new BlockModelLighter();
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        boolean ambientOcclusion = Minecraft.getInstance().options.ambientOcclusion().get();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(8192)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            PoseStack pose = new PoseStack();
            QuadInstance instance = new QuadInstance();
            instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
            for (int i = 0; i < occupied; i++) {
                int index = section.occupiedIndex(i);
                int x = (index >> 8) & 15;
                int y = (index >> 4) & 15;
                int z = index & 15;
                SimulationBlock block = section.blockAt(x, y, z);
                if (block == null) {
                    continue;
                }
                if (x < minX) {
                    minX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (z < minZ) {
                    minZ = z;
                }
                if (x + 1.0 > maxX) {
                    maxX = x + 1.0;
                }
                if (y + 1.0 > maxY) {
                    maxY = y + 1.0;
                }
                if (z + 1.0 > maxZ) {
                    maxZ = z + 1.0;
                }
                pose.pushPose();
                pose.translate(x, y, z);
                PoseStack.Pose last = pose.last();
                BlockState state = block.state();
                List<BlockStateModelPart> parts = farlands$parts(state);
                getter.at(x, y, z, state);
                localPos.set(x, y, z);
                boolean smooth = ambientOcclusion && state.getLightEmission() == 0
                        && !parts.isEmpty() && parts.getFirst().useAmbientOcclusion();
                for (Direction direction : DIRECTIONS) {
                    if (farlands$hidden(solid, x, y, z, direction)) {
                        continue;
                    }
                    for (BlockStateModelPart part : parts) {
                        for (BakedQuad quad : part.getQuads(direction)) {
                            farlands$prepare(lighter, getter, localPos, direction, quad, instance, smooth);
                            builder.putBakedQuad(last, quad, instance);
                            quads++;
                        }
                    }
                }
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(null)) {
                        farlands$prepare(lighter, getter, localPos, null, quad, instance, smooth);
                        builder.putBakedQuad(last, quad, instance);
                        quads++;
                    }
                }
                pose.popPose();
            }
            if (quads == 0) {
                return null;
            }
            MeshData mesh = builder.buildOrThrow();
            try {
                ByteBuffer source = mesh.vertexBuffer();
                byte[] vertices = new byte[source.remaining()];
                source.duplicate().get(vertices);
                return new Mesh(vertices, quads * 4, new AABB(minX, minY, minZ, maxX, maxY, maxZ));
            } finally {
                mesh.close();
            }
        }
    }

    /**
     * 一个 quad 的着色：先按原版的门选平滑或平坦，再把 tint 乘上去。
     *
     * <p>
     * tint 用 {@code multiplyColor} 而不是覆盖：vanilla 的顺序是先铺方向明暗的灰、再乘 tint，覆盖会把明暗
     * 抹平。无 tint 层时乘 -1，等于不乘。
     */
    private static void farlands$prepare(BlockModelLighter lighter, SectionGetter getter,
            BlockPos.MutableBlockPos localPos, Direction direction, BakedQuad quad, QuadInstance instance,
            boolean smooth) {
        BlockState state = getter.state();
        if (smooth) {
            lighter.prepareQuadAmbientOcclusion(getter, state, localPos, quad, instance);
        } else {
            int light = direction == null ? BlockModelLighter.CHECK_LIGHT
                    : lighter.getLightCoords(state, getter, localPos.relative(direction));
            lighter.prepareQuadFlat(getter, state, localPos, light, quad, instance);
        }
        int tintIndex = quad.materialInfo().tintIndex();
        if (tintIndex >= 0) {
            instance.multiplyColor(getter.tintColor(tintIndex));
        }
    }

    /** 该方向上是否被邻块挡住。段外的邻块按空处理，所以边界面一律可见。 */
    private static boolean farlands$hidden(boolean[] solid, int x, int y, int z, Direction direction) {
        int nx = x + direction.getStepX();
        int ny = y + direction.getStepY();
        int nz = z + direction.getStepZ();
        if (nx < 0 || ny < 0 || nz < 0
                || nx >= SimulationSection.SIZE || ny >= SimulationSection.SIZE || nz >= SimulationSection.SIZE) {
            return false;
        }
        return solid[(nx << 8) | (ny << 4) | nz];
    }

    /** 该方块状态的模型部件表，首次取用时建。 */
    private static List<BlockStateModelPart> farlands$parts(BlockState state) {
        List<BlockStateModelPart> parts = PARTS.get(state);
        if (parts != null) {
            return parts;
        }
        BlockStateModelSet set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel model = set.get(state);
        java.util.ArrayList<BlockStateModelPart> built = new java.util.ArrayList<>();
        model.collectParts(RandomSource.create(0L), built);
        parts = List.copyOf(built);
        PARTS.put(state, parts);
        return parts;
    }

    /**
     * 段上的取数入口：一个可复用的 {@link BlockAndTintGetter}，把位置当作段内局部坐标。
     *
     * <p>
     * 不读世界坐标：模拟之地的坐标在 {@code BlockPos} 上已饱和。局部坐标落在 0 到 15，越出本段的采样点
     * 按 {@code floorDiv} 求列、查邻段，折回段内后交给容器。缺邻段按空气、光 0、群系取该 level 的默认值。
     *
     * <p>
     * 光走 {@link #getBrightness}，它必须覆写：接口默认实现读 {@code getLightEngine()}，而这里返回空引擎，
     * 不覆写会让整层的光恒为 0 且不报错。
     */
    private static final class SectionGetter implements BlockAndTintGetter {

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

        SectionGetter(SimulationSection section, Simulation simulation) {
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

        /** 段内局部坐标解析到段；同段直接返回本段，缺列返回 null。 */
        private SimulationSection sectionFor(int sampleX, int sampleZ) {
            int cellX = Math.floorDiv(sampleX, SimulationSection.SIZE);
            int cellZ = Math.floorDiv(sampleZ, SimulationSection.SIZE);
            if (cellX == 0 && cellZ == 0) {
                return this.section;
            }
            return this.simulation.sectionOrNull(this.section.sectionX() + cellX, this.section.sectionY(),
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
         * 样本点的群系。样本跨出本段时查邻段，邻段不在方阵里回落到默认群系。
         *
         * <p>
         * 半径最大 7，段边长 16，所以最多跨一列；折回段内后的局部坐标仍在 0 到 15。
         */
        private Holder<Biome> biomeAt(int sampleX, int sampleZ) {
            SimulationSection target = sectionFor(sampleX, sampleZ);
            if (target == null) {
                return this.simulation.defaultBiome();
            }
            return target.getNoiseBiome(fold(sampleX) >> 2, this.localY >> 2, fold(sampleZ) >> 2);
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            int localY = pos.getY();
            if (localY < 0 || localY >= SimulationSection.SIZE) {
                return 0;
            }
            SimulationSection target = sectionFor(pos.getX(), pos.getZ());
            if (target == null) {
                return 0;
            }
            return target.getLight(layer, fold(pos.getX()), localY, fold(pos.getZ()));
        }

        /** 按段内局部坐标取块状态。缺段与空格按空气。 */
        @Override
        public BlockState getBlockState(BlockPos pos) {
            int localY = pos.getY();
            if (localY < 0 || localY >= SimulationSection.SIZE) {
                return Blocks.AIR.defaultBlockState();
            }
            SimulationSection target = sectionFor(pos.getX(), pos.getZ());
            if (target == null) {
                return Blocks.AIR.defaultBlockState();
            }
            SimulationBlock block = target.blockAt(fold(pos.getX()), localY, fold(pos.getZ()));
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
}
