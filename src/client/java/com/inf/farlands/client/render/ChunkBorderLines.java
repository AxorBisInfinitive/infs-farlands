package com.inf.farlands.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * 区块边界线的每帧缓冲：端点以相机相对量存放，绘制时直接写顶点。
 *
 * <p>
 * 与 gizmo 管线的差别只有一条。那条管线把端点存成世界量级的 {@code Vec3}，渲染时再减相机，两个操作数都已被舍到各自
 * 量级的 ulp 网格上，16 格的相对关系在 {@code 2^57} 之上就没了。这里在发布时就把相机减掉，存的量级只有几十格，与
 * 世界坐标在哪个域无关。
 *
 * <p>
 * 端点由 {@link #begin} 给的相对原点加整数格偏移得到。偏移最大 48 格，相对原点的量级也不超过几十格，所以相加全程
 * 精确，不经过任何世界量级的 double。
 *
 * <p>
 * 线程：只有渲染线程写，抽取阶段写、渲染 pass 读，故用普通静态字段。
 */
public final class ChunkBorderLines {

    /** 一条线六个分量：起点三轴加终点三轴。 */
    private static final int STRIDE = 6;

    /** 初始容量按实测的 300 条留余量。 */
    private static final int INITIAL_LINES = 512;

    private static double[] coords = new double[STRIDE * INITIAL_LINES];

    private static int[] colors = new int[INITIAL_LINES];

    private static float[] widths = new float[INITIAL_LINES];

    private static int count;

    private static double originX;

    private static double originY;

    private static double originZ;

    private ChunkBorderLines() {
    }

    /** 清空并定下本帧的原点，三轴都是相机相对量。 */
    public static void begin(double relativeOriginX, double relativeOriginY, double relativeOriginZ) {
        count = 0;
        originX = relativeOriginX;
        originY = relativeOriginY;
        originZ = relativeOriginZ;
    }

    /** 清空。本帧不画时调，避免上一帧的线漏到这一帧。 */
    public static void clear() {
        count = 0;
    }

    /** 该帧是否有线段。 */
    public static boolean isEmpty() {
        return count == 0;
    }

    /** 一段线，坐标是相对原点的整数格偏移。 */
    public static void line(int x1, int y1, int z1, int x2, int y2, int z2, int color, float width) {
        ensure();
        int base = count * STRIDE;
        coords[base] = originX + x1;
        coords[base + 1] = originY + y1;
        coords[base + 2] = originZ + z1;
        coords[base + 3] = originX + x2;
        coords[base + 4] = originY + y2;
        coords[base + 5] = originZ + z2;
        colors[count] = color;
        widths[count] = width;
        count++;
    }

    /**
     * 一个 16 乘 16 的面网格，画在相对原点 {@code (ox, oy, oz)} 那一段的六个面之一上。
     *
     * <p>
     * 与旧实现逐句对应：{@code axis} 选面法向，{@code maxSide} 选正负两面，{@code step} 是格距；{@code step} 小于 4
     * 时 4 的倍数那几格跳过，因为四级粗线由调用方另画。
     */
    public static void faceGrid(int ox, int oy, int oz, int axis, boolean maxSide, int step, int color, float width) {
        int[] min = { ox, oy, oz };
        int fixed = maxSide ? min[axis] + 16 : min[axis];
        int a1 = (axis + 1) % 3;
        int a2 = (axis + 2) % 3;
        for (int v = step; v <= 16 - step; v += step) {
            if (step < 4 && (v & 3) == 0) {
                continue;
            }
            int[] p = { min[0], min[1], min[2] };
            int[] q = { min[0], min[1], min[2] };
            p[axis] = fixed;
            q[axis] = fixed;
            p[a1] = min[a1];
            q[a1] = min[a1] + 16;
            p[a2] = min[a2] + v;
            q[a2] = min[a2] + v;
            line(p[0], p[1], p[2], q[0], q[1], q[2], color, width);
            p[a1] = min[a1] + v;
            q[a1] = min[a1] + v;
            p[a2] = min[a2];
            q[a2] = min[a2] + 16;
            line(p[0], p[1], p[2], q[0], q[1], q[2], color, width);
        }
    }

    /**
     * 把本帧的线画出去，然后清空。
     *
     * <p>
     * 顶点以相机相对量直接写进 {@code PoseStack}，所以传进来的栈必须是恒等栈。调用点是
     * {@code DrawableGizmoPrimitives.render} 的头部，那时栈由 {@code LevelRenderer} 新建、未做任何变换。
     *
     * <p>
     * 不做近平面的 CPU 截断：vanilla 那条是为了避免整条线被它自己的 CPU 视锥判断剔掉，这里不判，交给 GPU 裁剪。
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffers) {
        if (count == 0) {
            return;
        }
        VertexConsumer builder = buffers.getBuffer(RenderTypes.lines());
        PoseStack.Pose pose = poseStack.last();
        int drawn = count;
        count = 0;
        for (int i = 0; i < drawn; i++) {
            int base = i * STRIDE;
            float x1 = (float) coords[base];
            float y1 = (float) coords[base + 1];
            float z1 = (float) coords[base + 2];
            float x2 = (float) coords[base + 3];
            float y2 = (float) coords[base + 4];
            float z2 = (float) coords[base + 5];
            float nx = x2 - x1;
            float ny = y2 - y1;
            float nz = z2 - z1;
            int color = colors[i];
            float width = widths[i];
            builder.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
            builder.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        }
    }

    /** 容量不够时翻倍。线段数由网格形状决定，稳定在 300 条上下，扩容只在前几帧发生。 */
    private static void ensure() {
        if (count < colors.length) {
            return;
        }
        int capacity = colors.length * 2;
        coords = java.util.Arrays.copyOf(coords, STRIDE * capacity);
        colors = java.util.Arrays.copyOf(colors, capacity);
        widths = java.util.Arrays.copyOf(widths, capacity);
    }
}
