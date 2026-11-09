package com.inf.farlands.simulation.int64.noise;

/**
 * 噪声路由，字段与顺序照 vanilla 的 {@code NoiseRouter}，逐项换成 {@link Density}。
 *
 * <p>这段顺序来自本仓库 {@code NoiseSystem.createRouter} 的默认实现，与构造器形参次序一致。
 */
public record Router(
        Density barrierNoise,
        Density fluidLevelFloodednessNoise,
        Density fluidLevelSpreadNoise,
        Density lavaNoise,
        Density temperature,
        Density vegetation,
        Density continents,
        Density erosion,
        Density depth,
        Density ridges,
        Density preliminarySurfaceLevel,
        Density finalDensity,
        Density veinToggle,
        Density veinRidged,
        Density veinGap) {

    /** 只换 finalDensity，其余照抄。与 {@code NoiseSystem.createRouter} 的默认体同义。 */
    public Router withFinalDensity(Density value) {
        return new Router(this.barrierNoise, this.fluidLevelFloodednessNoise, this.fluidLevelSpreadNoise,
                this.lavaNoise, this.temperature, this.vegetation, this.continents, this.erosion, this.depth,
                this.ridges, this.preliminarySurfaceLevel, value, this.veinToggle, this.veinRidged,
                this.veinGap);
    }
}
