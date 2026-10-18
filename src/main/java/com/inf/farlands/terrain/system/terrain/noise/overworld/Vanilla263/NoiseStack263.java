package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

/**
 * 26.3 的 NoiseStack，八度叠加。
 *
 * <p>累加与乘积全在 float 上：{@code amplitude} 是 float，{@code get} 返回 float，乘积再累加到
 * float。把它写成 double 累加会让末尾几位不同，而这条函数的输出正是整个混合噪声的位移。
 */
final class NoiseStack263 {

    private final Layer263[] layers;

    NoiseStack263(Layer263[] layers) {
        this.layers = layers;
    }

    float get(double x, double y, double z) {
        float value = 0.0F;
        for (Layer263 layer : this.layers) {
            double frequency = layer.frequency();
            value += layer.amplitude() * layer.noise().get(x * frequency, y * frequency, z * frequency);
        }
        return value;
    }

    /**
     * 无位移时的取值，按 26.3 缓冲路径的结合次序：先把比例与层频率相乘，再乘方块坐标。
     *
     * <p>与 {@link #get} 的 {@code (坐标 * 比例) * 频率} 只在末几位不同，但极端坐标下这点差会跨过
     * {@code Mth.floor} 的整数边界，同一个方块因此落到相邻的格点，噪声值整个换掉。26.3 的生成走缓冲，
     * 所以这里跟缓冲。
     *
     * <p>带位移的叶不走这里：26.3 的 {@code ShiftedXzSampler} 最终仍调 {@code Noise.get}，结合次序是
     * 先加位移再乘频率，与 {@link #get} 一致。
     */
    float getScaled(int blockX, int blockY, int blockZ, double xzScale, double yScale) {
        float value = 0.0F;
        for (Layer263 layer : this.layers) {
            double xz = xzScale * layer.frequency();
            double y = yScale * layer.frequency();
            value += layer.amplitude() * layer.noise().get(blockX * xz, blockY * y, blockZ * xz);
        }
        return value;
    }

    /** 一层八度。频率在 double 上，振幅在 float 上，与 26.3 的字段类型一致。 */
    record Layer263(GradientNoise263 noise, double frequency, float amplitude) {
    }
}
