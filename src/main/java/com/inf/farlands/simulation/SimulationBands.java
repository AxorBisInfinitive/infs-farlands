package com.inf.farlands.simulation;

import java.util.Arrays;

import com.inf.farlands.FarlandsConfig;

/**
 * 模拟层的竖直层分布：每 6 段一层，即方块 Y 上每 96 格一层。
 *
 * <p>
 * 枚举窗口的半宽取 {@link FarlandsConfig#verticalSimulationDistance}，与地形窗口同源，窗口内层数随之变。
 * 窗口里一个层都落不进时返回空数组，那一列就没有模拟段，这是窗口本身的语义。
 *
 * <p>
 * 服务端按它决定哪些层存在，客户端不再自己枚举；两侧对同一个函数取同样的结果，就不存在层集合不一致的问题。
 */
public final class SimulationBands {

    /** 层的竖直周期，以段计：6 段即 96 格。 */
    public static final int SECTION_PERIOD = 6;

    private SimulationBands() {
    }

    /**
     * 以 {@code centerSectionY} 为中心、半宽 {@code verticalSimulationDistance} 的窗口里，落在层格点上的段号，
     * 升序。
     *
     * <p>
     * 元素数约是 {@code 2 * 半宽 / SECTION_PERIOD + 1}，随窗口变。窗口比层距还窄时一个都落不进，返回空数组。
     */
    public static long[] sectionsNear(long centerSectionY) {
        int half = FarlandsConfig.verticalSimulationDistance;
        long low = centerSectionY - half;
        long high = centerSectionY + half;
        long first = Math.floorDiv(low, (long) SECTION_PERIOD) * SECTION_PERIOD;
        if (first < low) {
            first += SECTION_PERIOD;
        }
        long[] found = new long[2 * half / SECTION_PERIOD + 2];
        int count = 0;
        for (long sectionY = first; sectionY <= high && count < found.length; sectionY += SECTION_PERIOD) {
            found[count++] = sectionY;
        }
        return Arrays.copyOf(found, count);
    }
}
