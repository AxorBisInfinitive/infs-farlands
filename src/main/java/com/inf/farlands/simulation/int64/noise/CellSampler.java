package com.inf.farlands.simulation.int64.noise;

import com.inf.farlands.simulation.int64.util.Sink;

import net.minecraft.util.Mth;

/**
 * 按 cell 采样、块内三线性插值的求值器，行为对齐 vanilla 的 NoiseInterpolator。
 *
 * <p>角点落在 cellWidth 与 cellHeight 的全局整数倍上。一段的角点数是 (16/cw+1) 乘 (16/ch+1) 乘
 * (16/cw+1)。段基址是 16 的倍数，而 cw 与 ch 整除 16，所以段内的角点就是该段范围内的全局 cell 网格，
 * 不需要额外的对齐修正。段顶与段右的最后一圈角点落在段外一格，必须算：它与邻段的第一圈是同一个绝对
 * 坐标，两边因此取到同一个值。
 *
 * <p>三级插值的顺序是 Y、X、Z，与 vanilla 的 updateForY、updateForX、updateForZ 一一对应。顺序不能换：
 * 浮点加法不结合，换序会让末位变化。每一步直接调 Mth.lerp，即 p0 + alpha * (p1 - p0)，不另写等价式。
 *
 * <p>角点值是纯函数值，填充顺序不影响结果，所以本类只按一个固定顺序填数组。逐块取值时现取角点，
 * 不缓存中间量。
 *
 * <p>前置条件是 16 % cw == 0 且 16 % ch == 0，见 supports。不成立时由调用方退回逐块求值，本类不做补偿。
 */
public final class CellSampler {

    /** 段边长，方块。 */
    public static final int SECTION_SIZE = 16;

    private CellSampler() {
    }

    /** 该 cell 网格能否整除一段。不成立时不能用本类。 */
    public static boolean supports(int cellWidth, int cellHeight) {
        return cellWidth > 0 && cellHeight > 0
                && SECTION_SIZE % cellWidth == 0 && SECTION_SIZE % cellHeight == 0;
    }

    /**
     * 求满一段：先把角点算进数组，再逐块插值交给 sink。
     *
     * @param baseX 段首方块的绝对 X，16 的倍数
     * @param baseY 段首方块的绝对 Y，16 的倍数
     * @param baseZ 段首方块的绝对 Z，16 的倍数
     */
    public static void forEach(Density density, long baseX, long baseY, long baseZ,
            int cellWidth, int cellHeight, Sink sink) {
        int cornersX = SECTION_SIZE / cellWidth + 1;
        int cornersY = SECTION_SIZE / cellHeight + 1;
        int cornersZ = cornersX;
        double[] corners = new double[cornersX * cornersY * cornersZ];
        for (int i = 0; i < cornersX; i++) {
            long x = baseX + (long) i * cellWidth;
            for (int j = 0; j < cornersY; j++) {
                long y = baseY + (long) j * cellHeight;
                for (int k = 0; k < cornersZ; k++) {
                    long z = baseZ + (long) k * cellWidth;
                    corners[(i * cornersY + j) * cornersZ + k] = density.compute(x, y, z);
                }
            }
        }
        for (int ly = 0; ly < SECTION_SIZE; ly++) {
            int j = ly / cellHeight;
            double fy = (double) (ly - j * cellHeight) / (double) cellHeight;
            for (int lz = 0; lz < SECTION_SIZE; lz++) {
                int k = lz / cellWidth;
                double fz = (double) (lz - k * cellWidth) / (double) cellWidth;
                for (int lx = 0; lx < SECTION_SIZE; lx++) {
                    int i = lx / cellWidth;
                    double fx = (double) (lx - i * cellWidth) / (double) cellWidth;
                    double value = farlands$lerp(corners, i, j, k, cornersY, cornersZ, fx, fy, fz);
                    sink.accept(baseX + lx, baseY + ly, baseZ + lz, value);
                }
            }
        }
    }

    /** 一个 cell 的八个角点按 Y、X、Z 三级插值，与 vanilla 的四个、两个、一个 lerp 对应。 */
    private static double farlands$lerp(double[] corners, int i, int j, int k, int cornersY, int cornersZ,
            double fx, double fy, double fz) {
        double n000 = farlands$at(corners, i, j, k, cornersY, cornersZ);
        double n100 = farlands$at(corners, i + 1, j, k, cornersY, cornersZ);
        double n010 = farlands$at(corners, i, j + 1, k, cornersY, cornersZ);
        double n110 = farlands$at(corners, i + 1, j + 1, k, cornersY, cornersZ);
        double n001 = farlands$at(corners, i, j, k + 1, cornersY, cornersZ);
        double n101 = farlands$at(corners, i + 1, j, k + 1, cornersY, cornersZ);
        double n011 = farlands$at(corners, i, j + 1, k + 1, cornersY, cornersZ);
        double n111 = farlands$at(corners, i + 1, j + 1, k + 1, cornersY, cornersZ);
        double valueXZ00 = Mth.lerp(fy, n000, n010);
        double valueXZ10 = Mth.lerp(fy, n100, n110);
        double valueXZ01 = Mth.lerp(fy, n001, n011);
        double valueXZ11 = Mth.lerp(fy, n101, n111);
        double valueZ0 = Mth.lerp(fx, valueXZ00, valueXZ10);
        double valueZ1 = Mth.lerp(fx, valueXZ01, valueXZ11);
        return Mth.lerp(fz, valueZ0, valueZ1);
    }

    private static double farlands$at(double[] corners, int i, int j, int k, int cornersY, int cornersZ) {
        return corners[(i * cornersY + j) * cornersZ + k];
    }
}
