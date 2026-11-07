package com.inf.farlands.simulation.int64.noise;

import java.util.List;

/**
 * 一组噪声参数：首八度与各八度幅度。
 *
 * <p>幅度用 {@code List} 而不是数组，值语义的 equals 才成立，缓存与比较才能按内容判。
 */
public record NoiseParameters(int firstOctave, List<Double> amplitudes) {

    public NoiseParameters {
        amplitudes = List.copyOf(amplitudes);
    }
}
