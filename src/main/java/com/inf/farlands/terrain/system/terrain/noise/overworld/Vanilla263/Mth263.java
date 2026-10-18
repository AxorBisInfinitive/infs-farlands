package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

/**
 * 26.3 的四个 float 数学函数。
 *
 * <p>26.1.2 的 Mth 只有这四个的 double 重载。把 float 实参传进去会静默提升成 double 再收窄回
 * float，末位就与 26.3 不同，而编译期不报任何东西。所以移植体必须自带一份。
 *
 * <p>其余 float 数学可以直接用 26.1.2 的：{@code Mth.lerp(float,float,float)}、
 * {@code Mth.clamp(float,float,float)}、{@code Mth.square(float)}、{@code Mth.cube(float)}、
 * {@code Mth.sqrt(float)}。
 */
public final class Mth263 {

    private Mth263() {
    }

    /** 平滑插值权重，全 float 计算。 */
    public static float smoothstep(float x) {
        return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F);
    }

    /** 平滑插值的导数。 */
    public static float smoothstepDerivative(float x) {
        return 30.0F * x * x * (x - 1.0F) * (x - 1.0F);
    }

    /** 两次插值，先沿第一轴，再沿第二轴。 */
    public static float lerp2(float alpha1, float alpha2, float x00, float x10, float x01, float x11) {
        return lerp(alpha2, lerp(alpha1, x00, x10), lerp(alpha1, x01, x11));
    }

    /**
     * 三次插值。
     *
     * <p>角点下标顺序与 PerlinNoise 的 d000 到 d111 对齐：低位是 x，中位是 y，高位是 z。顺序颠倒
     * 不会报错，只会把地形拧成一个反向的插值场。
     */
    public static float lerp3(float alpha1, float alpha2, float alpha3, float x000, float x100, float x010,
            float x110, float x001, float x101, float x011, float x111) {
        return lerp(alpha3, lerp2(alpha1, alpha2, x000, x100, x010, x110),
                lerp2(alpha1, alpha2, x001, x101, x011, x111));
    }

    private static float lerp(float alpha, float p0, float p1) {
        return p0 + alpha * (p1 - p0);
    }
}
