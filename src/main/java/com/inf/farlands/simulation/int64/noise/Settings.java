package com.inf.farlands.simulation.int64.noise;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 一次填充的设置。
 *
 * <p>vanilla 的 {@code NoiseGeneratorSettings} 是维度一份，minY 与 height 描述整个维度。仿真按段填，
 * 所以这两个字段是段范围，由 {@link #withRange} 从原型换出；与段无关的那几项由原型持有。
 *
 * <p>surfaceRule、spawnTarget、disableMobGeneration、useLegacyRandomSource 不搬：这一层不出地表，
 * 也不生成生物。
 */
public record Settings(
        long minY,
        long height,
        int noiseSizeXZ,
        int noiseSizeY,
        BlockState defaultBlock,
        BlockState defaultFluid,
        Router router,
        long seaLevel,
        boolean aquifersEnabled,
        boolean oreVeinsEnabled) {

    /** cell 宽，单位方块。noiseSize 的 4 是一格 quart。 */
    public int cellWidth() {
        return this.noiseSizeXZ * 4;
    }

    /** cell 高，单位方块。 */
    public int cellHeight() {
        return this.noiseSizeY * 4;
    }

    /** 换出段范围，其余原样。 */
    public Settings withRange(long minY, long height) {
        return new Settings(minY, height, this.noiseSizeXZ, this.noiseSizeY, this.defaultBlock,
                this.defaultFluid, this.router, this.seaLevel, this.aquifersEnabled, this.oreVeinsEnabled);
    }
}
