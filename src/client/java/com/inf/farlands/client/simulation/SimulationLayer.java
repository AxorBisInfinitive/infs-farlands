package com.inf.farlands.client.simulation;

import java.util.function.Consumer;

import net.minecraft.world.level.block.Blocks;

/**
 * 模拟层的定义：一个固定的段高度，加一个绑定那一刻跑一次的生产者。
 *
 * <p>
 * 段高度用段号，不是方块号。段 {@code 2^56} 对应方块 {@code y = 2^60}，这也是当前层用的位置。层高于可玩段界，
 * 所以 {@code SimulationSection} 的 {@code eligible} 为真，写入不会被丢弃。
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

    private final long sectionY;

    private final Consumer<SimulationSection> producer;

    public SimulationLayer(long sectionY, Consumer<SimulationSection> producer) {
        this.sectionY = sectionY;
        this.producer = producer;
    }

    public long sectionY() {
        return this.sectionY;
    }

    /** 对新绑定的段跑一次生产。 */
    public void produce(SimulationSection section) {
        this.producer.accept(section);
    }

    /**
     * 测试层：段 {@code 2^56} 那一层，即方块 {@code y = 2^60}，沿 x 走一条正弦高度场，从段底填到曲面。
     *
     * <p>
     * 方法名沿用 grassPlane，内容已换成正弦，仅供测试。高度只随世界 x 变化，z 方向同高。相位用整数取模
     * 算：worldX 可到十亿级以上，直接对 double 取 sin 会在参数归约上丢精度。基线与幅度之和落在 0 到 14，
     * 不越出本段，其他段仍为空。
     */
    public static SimulationLayer grassPlane() {
        return new SimulationLayer(1L << 56, section -> {
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
