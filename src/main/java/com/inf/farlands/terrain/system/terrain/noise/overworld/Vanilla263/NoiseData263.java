package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 26.3 主世界密度链与矿脉用到的噪声参数表。
 *
 * <p>参数取自 {@code NoiseData}，与 26.1.2 同名条目逐字相同，所以不读数据包也不需要 {@code noise}
 * 目录下那 64 份 json。表里列本链与矿脉用到的 29 条；其余的如 {@code surface}、{@code badlands_*}
 * 只服务表面与材料规则，不在这里。
 *
 * <p>路径是 {@code minecraft:} 命名空间下的名字，与注册表键逐字一致。它是随机流的哈希输入，
 * 写错一个字符就是另一条噪声，而地形仍然看得过去。
 */
enum NoiseData263 {

    SHIFT("offset", -3, 1.0, 1.0, 1.0, 0.0),
    CONTINENTALNESS("continentalness", -9, 1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0),
    EROSION("erosion", -9, 1.0, 1.0, 0.0, 1.0, 1.0),
    RIDGE("ridge", -7, 1.0, 2.0, 1.0, 0.0, 0.0, 0.0),
    JAGGED("jagged", -16, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
    CAVE_ENTRANCE("cave_entrance", -7, 0.4, 0.5, 1.0),
    CAVE_LAYER("cave_layer", -8, 1.0),
    CAVE_CHEESE("cave_cheese", -8, 0.5, 1.0, 2.0, 1.0, 2.0, 1.0, 0.0, 2.0, 0.0),
    SPAGHETTI_ROUGHNESS("spaghetti_roughness", -5, 1.0),
    SPAGHETTI_ROUGHNESS_MODULATOR("spaghetti_roughness_modulator", -8, 1.0),
    SPAGHETTI_2D_MODULATOR("spaghetti_2d_modulator", -11, 1.0),
    SPAGHETTI_2D("spaghetti_2d", -7, 1.0),
    SPAGHETTI_2D_ELEVATION("spaghetti_2d_elevation", -8, 1.0),
    SPAGHETTI_2D_THICKNESS("spaghetti_2d_thickness", -11, 1.0),
    SPAGHETTI_3D_RARITY("spaghetti_3d_rarity", -11, 1.0),
    SPAGHETTI_3D_1("spaghetti_3d_1", -7, 1.0),
    SPAGHETTI_3D_2("spaghetti_3d_2", -7, 1.0),
    SPAGHETTI_3D_THICKNESS("spaghetti_3d_thickness", -8, 1.0),
    NOODLE("noodle", -8, 1.0),
    NOODLE_THICKNESS("noodle_thickness", -8, 1.0),
    NOODLE_RIDGE_A("noodle_ridge_a", -7, 1.0),
    NOODLE_RIDGE_B("noodle_ridge_b", -7, 1.0),
    PILLAR("pillar", -7, 1.0, 1.0),
    PILLAR_RARENESS("pillar_rareness", -8, 1.0),
    PILLAR_THICKNESS("pillar_thickness", -8, 1.0),
    ORE_VEININESS("ore_veininess", -8, 1.0),
    ORE_VEIN_A("ore_vein_a", -7, 1.0),
    ORE_VEIN_B("ore_vein_b", -7, 1.0),
    ORE_GAP("ore_gap", -5, 1.0);

    private final String path;
    private final int firstOctave;
    private final double[] amplitudes;

    NoiseData263(String path, int firstOctave, double... amplitudes) {
        this.path = path;
        this.firstOctave = firstOctave;
        this.amplitudes = amplitudes;
    }

    /**
     * 造出该噪声的采样器。
     *
     * <p>哈希输入是完整的 {@code minecraft:名字}，与 26.3 的 {@code Noises.instantiate} 一致。
     */
    NoiseStack263 create(PositionalRandomFactory noises, boolean enableFarlands) {
        return NormalNoise263.parity(this.firstOctave, this.amplitudes)
                .create(noises.fromHashOf("minecraft:" + this.path), enableFarlands);
    }
}
