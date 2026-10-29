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
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
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
    public static Mesh build(SimulationSection section) {
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
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(8192)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            PoseStack pose = new PoseStack();
            QuadInstance instance = new QuadInstance();
            instance.setColor(-1);
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
                for (Direction direction : DIRECTIONS) {
                    if (farlands$hidden(solid, x, y, z, direction)) {
                        continue;
                    }
                    for (BlockStateModelPart part : parts) {
                        for (BakedQuad quad : part.getQuads(direction)) {
                            builder.putBakedQuad(last, quad, instance);
                            quads++;
                        }
                    }
                }
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(null)) {
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
}
