package com.inf.farlands.terrain.system.terrain.block.misc.Inf32768;

import java.util.Arrays;
import java.util.Set;

import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无穷大形状的莫比乌斯带：中线是 Gerono 双纽线，半径 {@code radius} 格、半宽 {@code halfWidth} 格、
 * 一格厚。
 *
 * <p>
 * 中线是 `x = a·cos t`、`z = a·sin t·cos t`，跨度 2a 乘 a，交叉点在原点，交叉处速度 a√2 不为零，
 * 是横向交叉而不是尖点。带面沿用同一条隐式式：把中线某处沿法向的带符号偏移记作 d、竖直偏移记作 y，
 * 则带面是 `d·sin(t/2) - y·cos(t/2) = 0`，带宽约束是 `d² + y² <= w²`。
 *
 * <p>
 * 中线不能解析反解：按 x 反解在两瓣端部退化，那里切向沿 z，同一 x 上的偏移与切向同向；按极角反解
 * 在交叉点退化，两条臂方向不同。所以构造期按 (x, z) 列扫一遍中线，每列记下最近点与次近点的参数、
 * 面内带符号距离与该处半角的正余弦，运行时逐格只做查表与几次比较。端部与交叉点因此共用同一条判据。
 *
 * <p>
 * 每列必须存两层，而且按半周分槽：交叉点那两条带子共用同一批列而法向不同，只存一层会把另一条在
 * 那一片整段丢掉，表现为交叉处缺方块。分槽不能按距离取次近，同一条分支的相邻采样只隔不到 0.2 格，
 * 会把两个槽都占满；按 t 是否小于 π 分就没有这个问题。
 *
 * <p>
 * 颜色按 t 分八段，红起于交叉点的下左臂 `t = π/2`，品红止于走完一圈。段与段之间不是硬分界：每段的最后
 * {@link #BLEND} 那一截按坐标哈希抖动，混入下一段，所以两色之间是逐渐过渡的散点。接缝因此在紫与红
 * 之间也有一段混合。这是莫比乌斯带不可定向的直接后果：颜色若在整个带上连续，就必须走完一圈回到
 * 起点色。
 *
 * <p>
 * 只有带子是方块，其余全是空气。要看到干净的带子，surface、carver、decoration 三族都得选 VOID，
 * 否则 vanilla 地表会按高度图把它刷成草皮、雕刻会打洞、装饰会往上放地物。
 *
 * <p>
 * 四条判据写在声明层：半宽下界是单参数检查，半径与半宽的三条关系是跨字段规则。违反时创建世界页签在
 * 提交前拦下并标红两个框，落盘的配置则在读回时抛，不会等到构造器里才炸。
 */
public final class Inf32768BlockSystem implements BlockSystem {

    /** 带子的半厚。半角的正余弦乘上偏移后与它比较，就是一格厚。 */
    private static final double HALF_THICKNESS = 0.5;

    /** 颜色段数。取八段，使交叉点相隔半圈的两条带子正好落在红与绿上。 */
    private static final int COLORS = 8;

    /** 每段末尾用于混入下一段的长度占比。取零就退回硬分界。 */
    private static final float BLEND = 0.4F;

    /** 抖动阈值粒度，取值 0 到 63。 */
    private static final int DITHER_STEPS = 64;

    /** 中线采样段数。按 a = 64 时弧长约 480 格，每段不到四分之一格。 */
    private static final int SAMPLES = 2048;

    /** 沿法向涂表的步长，单位格。 */
    private static final double PAINT_STEP = 0.5;

    /** 查表列数上限，防止半径填得过大把内存吃光。 */
    private static final long MAX_CELLS = 1L << 20;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** 红到品红的八段，每段 45 度。 */
    private static final BlockState[] RAMP = {
            Blocks.RED_CONCRETE.defaultBlockState(),
            Blocks.ORANGE_CONCRETE.defaultBlockState(),
            Blocks.YELLOW_CONCRETE.defaultBlockState(),
            Blocks.LIME_CONCRETE.defaultBlockState(),
            Blocks.GREEN_CONCRETE.defaultBlockState(),
            Blocks.CYAN_CONCRETE.defaultBlockState(),
            Blocks.BLUE_CONCRETE.defaultBlockState(),
            Blocks.MAGENTA_CONCRETE.defaultBlockState(),
    };

    /**
     * 声明项：瓣的半长与带子半宽。
     *
     * <p>
     * 半宽的下界是单参数判据，写在 spec 上。另外三条都由这对参数共同决定：半径必须大于半宽、外缘不得
     * 越过可玩上限、查表列数不得超上限，写成跨字段规则，失败时两个框一起标红。
     *
     * <p>
     * 三条派生判据与构造器分配数组时用的是同一个 {@link #geometry}，两边各写一份算式迟早会漂移，而
     * 漂移的后果是判据放行、构造时按另一份算式炸掉。
     */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofInt("radius").define(64).build(),
            SystemParamSpec.ofInt("halfWidth").define(8)
                    .check(v -> v >= 1,
                            v -> Component.translatable(
                                    "createWorld.tab.infs-farlands.param.error.half_width", "halfWidth", v))
                    .build())
            .withConstraint(
                    SystemParams.rule(Set.of("radius", "halfWidth"), args -> {
                        int radius = args.getInt("radius");
                        int halfWidth = args.getInt("halfWidth");
                        return radius > halfWidth ? null
                                : Component.translatable(
                                        "createWorld.tab.infs-farlands.param.error.radius_exceeds_half_width",
                                        radius, halfWidth);
                    }),
                    SystemParams.rule(Set.of("radius", "halfWidth"), args -> {
                        long outer = outerEdge(args.getInt("radius"), args.getInt("halfWidth"));
                        return outer <= WorldBounds.MAX_PLAYABLE_BLOCK ? null
                                : Component.translatable(
                                        "createWorld.tab.infs-farlands.param.error.outer_edge",
                                        outer, WorldBounds.MAX_PLAYABLE_BLOCK);
                    }),
                    SystemParams.rule(Set.of("radius", "halfWidth"), args -> {
                        long cells = geometry(args.getInt("radius"), args.getInt("halfWidth")).cells();
                        return cells <= MAX_CELLS ? null
                                : Component.translatable(
                                        "createWorld.tab.infs-farlands.param.error.table_cells",
                                        cells, MAX_CELLS);
                    }));

    /** 一块查表几何。全在 long 上算再落回 int，int 溢出不会让上界判据失效。 */
    private record Geometry(int minX, int minZ, int sizeX, int sizeZ, long cells) {
    }

    /** 带子外缘，取 long 以免两个 int 相加回绕。 */
    private static long outerEdge(int radius, int halfWidth) {
        return (long) radius + (long) halfWidth;
    }

    /**
     * 由两个参数算出包围盒与列数。
     *
     * <p>
     * 中线在 z 方向的最远点是 a/2，radius 为奇数时它是 x.5，整数除法会截掉半格并把最外侧裁掉，所以
     * halfZ 向上取整。
     */
    private static Geometry geometry(int radius, int halfWidth) {
        long outer = outerEdge(radius, halfWidth);
        long halfZ = ((long) radius + 1L) / 2L;
        long sizeX = 2L * outer + 1L;
        long sizeZ = 2L * (halfZ + halfWidth) + 1L;
        return new Geometry((int) -outer, (int) -(halfZ + halfWidth), (int) sizeX, (int) sizeZ, sizeX * sizeZ);
    }

    private final int radius;
    private final int halfWidth;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;

    private final double widthSq;

    /** 每列到中线的带符号距离，正无穷表示该列不在带内。第一层只收上半周 t < π 的采样。 */
    private final float[] distance;
    private final float[] halfSin;
    private final float[] halfCos;
    /** 每列的颜色相位，取值 0 到 1，乘段数即色号位置。 */
    private final float[] phase;

    /**
     * 第二层只收下半周 t >= π 的采样。交叉点那两条分支分别在 t = π/2 与 3π/2，一上一下，按半周
     * 分槽才能让它们各占一层；按距离取次近不行，同一条分支的相邻采样只隔不到 0.2 格，会把两个槽
     * 都占满，另一条分支一次都进不来。
     */
    private final float[] distance2;
    private final float[] halfSin2;
    private final float[] halfCos2;
    private final float[] phase2;

    public Inf32768BlockSystem(SystemArgs args) {
        this.radius = args.getInt("radius");
        this.halfWidth = args.getInt("halfWidth");
        Geometry geometry = geometry(this.radius, this.halfWidth);
        this.minX = geometry.minX();
        this.minZ = geometry.minZ();
        this.sizeX = geometry.sizeX();
        this.sizeZ = geometry.sizeZ();
        this.widthSq = (double) this.halfWidth * this.halfWidth;
        // 三条上界已在声明层拦过，这里的 toIntExact 只兜住绕过注册表直接构造的那条路：列数超出 int
        // 时给一条指到列数的异常，而不是让它落到数组分配上变成负长度或 OOM。
        int total = Math.toIntExact(geometry.cells());
        this.distance = new float[total];
        this.halfSin = new float[total];
        this.halfCos = new float[total];
        this.phase = new float[total];
        this.distance2 = new float[total];
        this.halfSin2 = new float[total];
        this.halfCos2 = new float[total];
        this.phase2 = new float[total];
        this.paint();
    }

    /**
     * 扫一遍中线，按半周分槽写进两张表。
     *
     * <p>
     * 距离在列中心上重新算，而不是用涂表的步长：步长只是用来找出哪些列落在法向的带宽里，真正写进
     * 表的是该列中心到法线的带符号投影，因此一格厚这个判据不会因为步长而失真。
     */
    private void paint() {
        Arrays.fill(this.distance, Float.POSITIVE_INFINITY);
        Arrays.fill(this.distance2, Float.POSITIVE_INFINITY);
        for (int i = 0; i < SAMPLES; i++) {
            double t = Math.PI * 2.0 * i / SAMPLES;
            double ct = Math.cos(t);
            double st = Math.sin(t);
            double px = this.radius * ct;
            double pz = this.radius * st * ct;
            double tx = -this.radius * st;
            double tz = this.radius * Math.cos(2.0 * t);
            double len = Math.hypot(tx, tz);
            if (len <= 0.0) {
                continue;
            }
            double nx = tz / len;
            double nz = -tx / len;
            float hs = (float) Math.sin(t * 0.5);
            float hc = (float) Math.cos(t * 0.5);
            float ph = phaseOf(t);
            boolean upper = t < Math.PI;
            float[] dists = upper ? this.distance : this.distance2;
            float[] sins = upper ? this.halfSin : this.halfSin2;
            float[] coss = upper ? this.halfCos : this.halfCos2;
            float[] phases = upper ? this.phase : this.phase2;
            for (double s = -this.halfWidth; s <= this.halfWidth; s += PAINT_STEP) {
                int cx = (int) Math.round(px + nx * s);
                int cz = (int) Math.round(pz + nz * s);
                int ix = cx - this.minX;
                int iz = cz - this.minZ;
                if (ix < 0 || ix >= this.sizeX || iz < 0 || iz >= this.sizeZ) {
                    continue;
                }
                int idx = ix * this.sizeZ + iz;
                double d = (cx - px) * nx + (cz - pz) * nz;
                double ad = Math.abs(d);
                if (ad > this.halfWidth) {
                    continue;
                }
                if (ad < Math.abs(dists[idx])) {
                    dists[idx] = (float) d;
                    sins[idx] = hs;
                    coss[idx] = hc;
                    phases[idx] = ph;
                }
            }
        }
    }

    @Override
    public BlockState fillBlock(int x, int y, int z) {
        if (y < -this.halfWidth || y > this.halfWidth) {
            return AIR;
        }
        int ix = x - this.minX;
        int iz = z - this.minZ;
        if (ix < 0 || ix >= this.sizeX || iz < 0 || iz >= this.sizeZ) {
            return AIR;
        }
        int idx = ix * this.sizeZ + iz;
        BlockState state = layer(this.distance[idx], this.halfSin[idx], this.halfCos[idx], this.phase[idx],
                x, y, z);
        if (state == null) {
            state = layer(this.distance2[idx], this.halfSin2[idx], this.halfCos2[idx], this.phase2[idx],
                    x, y, z);
        }
        return state == null ? AIR : state;
    }

    /** 一层判据：带宽、带厚。成立返回该层的方块，不成立返回 null。 */
    private BlockState layer(float d, float hs, float hc, float ph, int x, int y, int z) {
        if (d == Float.POSITIVE_INFINITY) {
            return null;
        }
        if ((double) d * d + (double) y * y > this.widthSq) {
            return null;
        }
        double h = d * hs - y * hc;
        if (Math.abs(h) >= HALF_THICKNESS) {
            return null;
        }
        return RAMP[colorAt(ph, x, y, z)];
    }

    /**
     * 相位到色号。每段最后 {@link #BLEND} 那一截按坐标哈希抖动混入下一段，所以段与段之间是散点过渡
     * 而不是一条直线。哈希只依赖坐标，同一格每次生成都是同一个颜色。
     */
    private static int colorAt(float phase, int x, int y, int z) {
        float p = phase * COLORS;
        int base = (int) p;
        if (base >= COLORS) {
            base = COLORS - 1;
        }
        float k = (p - base - (1.0F - BLEND)) / BLEND;
        if (k <= 0.0F) {
            return base;
        }
        return k * DITHER_STEPS > dither(x, y, z) ? (base + 1) % COLORS : base;
    }

    /** 坐标哈希，取六位做抖动阈值。乘的是奇数，位宽内回绕，因此不需要取模。 */
    private static int dither(int x, int y, int z) {
        int h = x * 0x9E3779B1 + y * 0x85EBCA77 + z * 0xC2B2AE3D;
        h ^= h >>> 15;
        h *= 0x2545F491;
        h ^= h >>> 13;
        return h & (DITHER_STEPS - 1);
    }

    /** t 到颜色相位。红起于交叉点的下左臂 t = π/2，接缝在它与上右臂之间。 */
    private static float phaseOf(double t) {
        double p = (t - Math.PI * 0.5) / (Math.PI * 2.0);
        p -= Math.floor(p);
        return (float) p;
    }
}
