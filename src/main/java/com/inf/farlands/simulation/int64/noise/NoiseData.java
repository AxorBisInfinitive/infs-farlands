package com.inf.farlands.simulation.int64.noise;

import net.minecraft.resources.Identifier;

/**
 * 一份噪声的声明：稳定 id 加参数。
 *
 * <p>id 是按 seed 重建的唯一抓手，vanilla 那边取的是 Holder 的 key。参数随声明一起带上，重建时不必回查注册表。
 */
public record NoiseData(Identifier id, NoiseParameters parameters) {
}
