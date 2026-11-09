package com.inf.farlands.simulation.int64.noise.instance;

/**
 * vanilla {@code SimplexNoise} 的梯度表与点积。
 *
 * <p>vanilla 里这两项都是 {@code protected}，跨包取不到，只能逐字复制。表有十六行，其中四行与前十二行重
 * 复，那是 vanilla 的原样，不要顺手去重：{@code hash & 15} 取到的下标决定地形。
 */
final class SimplexGradients {

    static final int[][] GRADIENT = {
            { 1, 1, 0 },
            { -1, 1, 0 },
            { 1, -1, 0 },
            { -1, -1, 0 },
            { 1, 0, 1 },
            { -1, 0, 1 },
            { 1, 0, -1 },
            { -1, 0, -1 },
            { 0, 1, 1 },
            { 0, -1, 1 },
            { 0, 1, -1 },
            { 0, -1, -1 },
            { 1, 1, 0 },
            { 0, -1, 1 },
            { -1, 1, 0 },
            { 0, -1, -1 }
    };

    private SimplexGradients() {
    }

    static double dot(int[] g, double x, double y, double z) {
        return g[0] * x + g[1] * y + g[2] * z;
    }
}
