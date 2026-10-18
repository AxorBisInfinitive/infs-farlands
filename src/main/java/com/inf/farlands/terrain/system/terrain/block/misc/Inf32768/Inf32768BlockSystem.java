package com.inf.farlands.terrain.system.terrain.block.misc.Inf32768;

import java.util.Arrays;

import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.util.world.WorldBounds;

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
 * 每列必须存两层：两条带子在交叉点附近共用同一批列，而它们的法向不同，只存最近的一层会把另一条
 * 带子在那一片整段丢掉，表现为交叉处缺方块。
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
 * 参数在构造期校验，违反直接抛：世界起不来，能立刻指到是哪个参数，而不是等到生成线程上才炸。查表
 * 规模也有上限，半径大到表撑不下时同样在构造期抛。
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

    /** 声明项：瓣的半长与带子半宽。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofInt("radius").define(64).build(),
            SystemParamSpec.ofInt("halfWidth").define(8).build());

    private final int radius;
    private final int halfWidth;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;

    private final double widthSq;

    /** 每列到中线的带符号距离，正无穷表示该列不在带内。第一层是最近点。 */
    private final float[] distance;
    private final float[] halfSin;
    private final float[] halfCos;
    /** 每列的颜色相位，取值 0 到 1，乘段数即色号位置。 */
    private final float[] phase;

    /** 次近的一层。两条带子在交叉点附近共用同一批列，只存最近的一层会把另一条整段丢掉。 */
    private final float[] distance2;
    private final float[] halfSin2;
    private final float[] halfCos2;
    private final float[] phase2;

    public Inf32768BlockSystem(SystemArgs args) {
        this.radius = args.getInt("radius");
        this.halfWidth = args.getInt("halfWidth");
        // 内缘要留在中心之外，否则两瓣的带子在同一处重叠，画出来是一团。
        if (this.halfWidth < 1 || this.radius <= this.halfWidth) {
            throw new IllegalArgumentException(
                    "farlands: inf32768 needs halfWidth >= 1 and radius > halfWidth, got "
                            + this.radius + " and " + this.halfWidth);
        }
        // 外缘是带子上离原点最远的一格，越过可玩界就写不进去，那些格会被静默丢掉。
        long outer = (long) this.radius + this.halfWidth;
        if (outer > WorldBounds.MAX_PLAYABLE_BLOCK) {
            throw new IllegalArgumentException("farlands: inf32768 outer edge " + outer
                    + " exceeds the playable limit " + WorldBounds.MAX_PLAYABLE_BLOCK);
        }
        this.minX = -(int) outer;
        this.minZ = -(this.radius / 2 + this.halfWidth);
        this.sizeX = 2 * (int) outer + 1;
        this.sizeZ = 2 * (this.radius / 2 + this.halfWidth) + 1;
        long cells = (long) this.sizeX * this.sizeZ;
        if (cells > MAX_CELLS) {
            throw new IllegalArgumentException("farlands: inf32768 lookup needs " + cells
                    + " columns, cap is " + MAX_CELLS);
        }
        this.widthSq = (double) this.halfWidth * this.halfWidth;
        this.distance = new float[this.sizeX * this.sizeZ];
        this.halfSin = new float[this.sizeX * this.sizeZ];
        this.halfCos = new float[this.sizeX * this.sizeZ];
        this.phase = new float[this.sizeX * this.sizeZ];
        this.distance2 = new float[this.sizeX * this.sizeZ];
        this.halfSin2 = new float[this.sizeX * this.sizeZ];
        this.halfCos2 = new float[this.sizeX * this.sizeZ];
        this.phase2 = new float[this.sizeX * this.sizeZ];
        this.paint();
    }

    /**
     * 扫一遍中线，把每列最近与次近的两个点的参数与距离写进表。
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
                if (ad < Math.abs(this.distance[idx])) {
                    this.distance2[idx] = this.distance[idx];
                    this.halfSin2[idx] = this.halfSin[idx];
                    this.halfCos2[idx] = this.halfCos[idx];
                    this.phase2[idx] = this.phase[idx];
                    this.distance[idx] = (float) d;
                    this.halfSin[idx] = hs;
                    this.halfCos[idx] = hc;
                    this.phase[idx] = ph;
                } else if (ad < Math.abs(this.distance2[idx])) {
                    this.distance2[idx] = (float) d;
                    this.halfSin2[idx] = hs;
                    this.halfCos2[idx] = hc;
                    this.phase2[idx] = ph;
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
