package com.inf.farlands.client.simulation;

import java.util.Arrays;
import java.util.function.Consumer;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.world.level.block.Blocks;

/**
 * 模拟层的定义：一个竖直周期，加一个绑定那一刻跑一次的生产者。
 *
 * <p>
 * 层按段格点分布，每 {@link #SECTION_PERIOD} 段一层，即方块 Y 上每 96 格一层。高度场只随世界 X 变化，所以竖直
 * 方向上是同一片正弦原地复制上去。
 *
 * <p>
 * 枚举窗口的半宽取 {@link FarlandsConfig#verticalSimulationDistance}，与地形窗口同源，窗口内层数随之变。玩家的
 * 竖直位置一变，进出窗口的层由方阵对账解绑与绑定。窗口里一个层都落不进时返回空数组，那一列就没有模拟段，这是窗口
 * 本身的语义。
 *
 * <p>
 * 落进可玩方块界的层不需要特殊判定：{@code SimulationSection} 的 {@code eligible} 会把它们的写入静默丢掉，
 * 而 {@code Simulation.commit} 不让它们进快照。
 *
 * <p>
 * 生产者只在列被绑定时跑一次，对应原版「段被加载即编译一次」的节奏。
 */
public final class SimulationLayer {

    /** 正弦的周期，以方块计。32 格跨两个段，跨段接续看得出来。 */
    private static final int PERIOD = 32;

    /** 高度场的基线。与幅度相加后落在段内 0 到 14。 */
    private static final int BASE = 7;

    private static final int AMPLITUDE = 7;

    /** 层的竖直周期，以段计：6 段即 96 格。 */
    private static final int SECTION_PERIOD = 6;

    private final Consumer<SimulationSection> producer;

    public SimulationLayer(Consumer<SimulationSection> producer) {
        this.producer = producer;
    }

    /** 对新绑定的段跑一次生产。 */
    public void produce(SimulationSection section) {
        this.producer.accept(section);
    }

    /**
     * 以 {@code centerSectionY} 为中心、半宽 {@code verticalSimulationDistance} 的窗口里，落在层格点上的段号，
     * 升序。
     *
     * <p>
     * 元素数约是 {@code 2 * verticalSimulationDistance / SECTION_PERIOD + 1}，随窗口变。窗口比层距还窄时一个都
     * 落不进，返回空数组。
     */
    public long[] sectionsNear(long centerSectionY) {
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

    /**
     * 测试层：每 6 段一层，沿 x 走一条正弦高度场，从段底填到曲面。
     *
     * <p>
     * 方法名沿用 grassPlane，内容已换成正弦，仅供测试。高度只随世界 x 变化，z 方向同高。相位用整数取模算：
     * worldX 可到十亿级以上，直接对 double 取 sin 会在参数归约上丢精度。基线与幅度之和落在 0 到 14，不越出本段，
     * 段内其余高度仍为空。
     */
    public static SimulationLayer grassPlane() {
        return new SimulationLayer(section -> {
            SimulationBlock block = SimulationBlock.full(Blocks.GRASS_BLOCK.defaultBlockState());
            long baseX = section.sectionX() * SimulationSection.SIZE;
            for (int x = 0; x < SimulationSection.SIZE; x++) {
                int phase = (int) Math.floorMod(baseX + x, (long) PERIOD);
                int height = BASE + (int) Math.round(AMPLITUDE * Math.sin(2.0 * Math.PI * phase / PERIOD));
                for (int z = 0; z < SimulationSection.SIZE; z++) {
                    for (int y = 0; y <= height; y++) {
                        section.set(x, y, z, block);
                    }
                }
            }
        });
    }
}
