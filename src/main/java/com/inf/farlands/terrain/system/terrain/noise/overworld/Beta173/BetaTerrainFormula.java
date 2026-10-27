package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

/**
 * beta 1.7.3 地形密度公式。
 *
 * <p>常量与分支顺序逐条对应 beta 的密度公式。顺序本身就是契约：陡峭度分母先乘湿润调制再钳上界，
 * 海平面偏移在干燥分支里把陡峭度清零，中心以下陡峭度乘四。三处顺序任何一处颠倒都会改变地形形状。
 *
 * <p>顶部渐消项在原始世界里把世界顶强制成空气。无限 Y 下 cy 没有上限，渐消因子必须钳到 1，
 * 否则它会由负翻正、把空气变成实心方块。开关由系统参数给，默认关。
 */
public final class BetaTerrainFormula {

    private static final double CELL_H = 4.0;
    private static final double CELL_V = 8.0;
    /** beta 的 Y cell 网格数，原始世界高 128 格。 */
    private static final double Y_CELLS = 17.0;
    /** 高度缩放，beta 原样为 1/512。取 1/256 会让地表幅度翻倍。 */
    private static final double HEIGHT_SCALE = 1.0 / 512.0;
    /** 顶部渐消带的起始 cell，原始世界顶往下三格。 */
    private static final double TOP_START_CELL = Y_CELLS - 4.0;

    private BetaTerrainFormula() {
    }

    /**
     * 一列的两个气候量，都只依赖 2D 采样与温度湿度，与 Y 无关。
     *
     * <p>
     * {@code factor} 是陡峭度分母，{@code midCell} 是海平面中心。prelim 的不动点要靠它们把 Y 解出来，所以从
     * {@link #density} 里拆出来单独给。
     */
    record Climate(double factor, double midCell) {
    }

    /** 该列的气候量。采样 3 的调制与采样 4 的干燥分支在同一处收口。 */
    static Climate climate(double sample3, double sample4, double temperature, double humidity) {
        // 湿润调制。现代温度可以为负，所以乘积先钳到 [0,1]。
        double climateProduct = Math.max(0.0, Math.min(1.0, humidity * temperature));
        double var25 = 1.0 - climateProduct;
        var25 = var25 * var25;
        var25 = var25 * var25;
        var25 = 1.0 - var25;

        double var27 = (sample3 / 512.0 + 0.5) * var25;
        if (var27 > 1.0) {
            var27 = 1.0;
        }

        // 湿度决定海平面中心偏移。干燥分支返回负值，中心下移并且陡峭度清零，湿润分支上移。
        double v = sample4 / 8000.0;
        if (v < 0.0) {
            v = -v * 0.3;
        }
        v = v * 3.0 - 2.0;
        double hSea;
        if (v < 0.0) {
            v /= 2.0;
            if (v < -1.0) {
                v = -1.0;
            }
            v /= 1.4;
            v /= 2.0;
            hSea = v;
            var27 = 0.0;
        } else {
            if (v > 1.0) {
                v = 1.0;
            }
            hSea = v / 8.0;
        }
        if (var27 < 0.0) {
            var27 = 0.0;
        }
        var27 += 0.5;

        double yMidCell = Y_CELLS / 2.0 + hSea * (Y_CELLS / 16.0) * 4.0;
        return new Climate(var27, yMidCell);
    }

    /**
     * 该坐标的高度项，单位方块，等于 {@code lerp(lower, upper, blend) / 512}。
     *
     * <p>
     * 三个通道的采样点：0 与 1 用整数 cell 坐标，2 用浮点坐标做低频采样。调用点传 cell 角点的方块坐标时，
     * 浮点那一路正好等于 cell 坐标本身。
     */
    static double height(int blockX, int blockY, int blockZ, BetaTerrainNoise noise) {
        double cx = blockX / CELL_H;
        double cy = blockY / CELL_V;
        double cz = blockZ / CELL_H;

        // 通道 0 与 1 用整数 cell 坐标，匹配 beta 的 cell 网格采样。通道 2 用浮点坐标做低频采样。
        int cellX = Math.floorDiv(blockX, (int) CELL_H);
        int cellY = Math.floorDiv(blockY, (int) CELL_V);
        int cellZ = Math.floorDiv(blockZ, (int) CELL_H);

        double lower = noise.sample(0, (double) cellX, (double) cellY, (double) cellZ);
        double upper = noise.sample(1, (double) cellX, (double) cellY, (double) cellZ);
        // blend 先除以 20 再偏移 0.5，之后钳到 [0,1]：等价于 beta 先判负再判大于 1 的两个分支。
        double blend = noise.sample(2, cx, cy, cz) / 20.0 + 0.5;
        blend = blend < 0.0 ? 0.0 : (blend > 1.0 ? 1.0 : blend);

        return lerp(lower, upper, blend) * HEIGHT_SCALE;
    }

    public static double density(int blockX, int blockY, int blockZ, BetaTerrainNoise noise,
            double temperature, double humidity, double sample3, double sample4, boolean topFade) {
        double cy = blockY / CELL_V;

        double height = height(blockX, blockY, blockZ, noise);
        Climate climate = climate(sample3, sample4, temperature, humidity);

        double bias = (cy - climate.midCell()) * 12.0;
        bias /= climate.factor();
        if (cy < climate.midCell()) {
            bias *= 4.0;
        }

        double density = height - bias;

        if (topFade && cy > TOP_START_CELL) {
            double t = (cy - TOP_START_CELL) / 3.0;
            if (t > 1.0) {
                t = 1.0;
            }
            density = density * (1.0 - t) + (-10.0) * t;
        }

        return density;
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }
}
