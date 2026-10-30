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
 * 群系色按原版口径算：每个 quad 的 tint 层由 {@code BlockColors} 的 tint 源给出，取数走自建的
 * {@link BlockAndTintGetter}，它把位置当作段内局部坐标，改用 long 世界坐标与段内 quart 群系。混合半径保留，
 * 样本跨出本段时查邻段，邻段不在方阵里则回落到该 level 的默认群系。
 *
 * <p>
 * 顶点按四边形四元一组顺序写出，与共享的顺序索引缓冲 {@code getSequentialBuffer(QUADS)} 的序列配套。
 */
public final class SimulationMesher {

    /** 方块光与天光都是 15。 */
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

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
        Tint tint = new Tint(section, simulation);
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(8192)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            PoseStack pose = new PoseStack();
            QuadInstance instance = new QuadInstance();
            instance.setLightCoords(FULL_BRIGHT);
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
                List<BlockStateModelPart> parts = farlands$parts(block.state());
                tint.prepare(x, y, z, block.state());
                for (Direction direction : DIRECTIONS) {
                    if (farlands$hidden(solid, x, y, z, direction)) {
                        continue;
                    }
                    for (BlockStateModelPart part : parts) {
                        for (BakedQuad quad : part.getQuads(direction)) {
                            instance.setColor(tint.colorOf(quad));
                            builder.putBakedQuad(last, quad, instance);
                            quads++;
                        }
                    }
                }
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(null)) {
                        instance.setColor(tint.colorOf(quad));
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
     * tint 取色的上下文：一个可复用的 {@link BlockAndTintGetter}，按当前块给 tint 源提供群系与坐标。
     *
     * <p>
     * 位置只用段内局部坐标，不读传入的 {@code BlockPos}：模拟之地的世界坐标在 BlockPos 上已饱和，而 local
     * 坐标落在 0 到 15 内，不会饱和。tint 源的 {@code pos} 参数在 vanilla 里也只被原样转给群系查询，见
     * {@code BlockTintSources} 的各个实现，所以忽略它是等价的。
     *
     * <p>
     * 混合按原版 {@code ClientLevel.calculateBlockTint} 的口径复刻：XZ 各取 dist 邻域、Y 不混合、样本点用
     * 它自己的世界坐标、通道分别取整平均。样本跨出本段时查邻段，邻段不在方阵里则回落到该 level 的默认群系，
     * 即 void。
     */
    private static final class Tint implements BlockAndTintGetter {

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

        Tint(SimulationSection section, Simulation simulation) {
            this.section = section;
            this.simulation = simulation;
        }

        /** 切到该块并算好它的 tint 色。每个块一次，同一块的多个 quad 复用。 */
        void prepare(int x, int y, int z, BlockState state) {
            this.localX = x;
            this.localY = y;
            this.localZ = z;
            this.state = state;
            List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
            if (this.colors.length < sources.size()) {
                this.colors = new int[sources.size()];
            }
            this.colorCount = sources.size();
            for (int i = 0; i < sources.size(); i++) {
                this.colors[i] = sources.get(i).colorInWorld(state, this, this.unused);
            }
        }

        /** 该 quad 的颜色。没有 tint 层时为 -1，即顶点保持白色。 */
        int colorOf(BakedQuad quad) {
            int tintIndex = quad.materialInfo().tintIndex();
            return tintIndex >= 0 && tintIndex < this.colorCount ? this.colors[tintIndex] : -1;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
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
            int cellX = Math.floorDiv(sampleX, SimulationSection.SIZE);
            int cellZ = Math.floorDiv(sampleZ, SimulationSection.SIZE);
            if (cellX == 0 && cellZ == 0) {
                return this.section.getNoiseBiome(sampleX >> 2, this.localY >> 2, sampleZ >> 2);
            }
            SimulationSection neighbor = this.simulation.sectionOrNull(
                    this.section.sectionX() + cellX, this.section.sectionY(), this.section.sectionZ() + cellZ);
            if (neighbor == null) {
                return this.simulation.defaultBiome();
            }
            return neighbor.getNoiseBiome((sampleX - cellX * SimulationSection.SIZE) >> 2, this.localY >> 2,
                    (sampleZ - cellZ * SimulationSection.SIZE) >> 2);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        /** 当前块的状态，与 pos 无关：tint 源不读这个方法。 */
        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.state;
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
