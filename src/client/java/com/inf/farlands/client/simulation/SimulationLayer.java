package com.inf.farlands.client.simulation;

import java.util.function.Consumer;

import net.minecraft.world.level.block.Blocks;

/**
 * 模拟层的定义：一个固定的段高度，加一个绑定那一刻跑一次的生产者。
 *
 * <p>
 * 段高度用段号，不是方块号。方块 {@code 2^40} 落在段 {@code 2^36} 的 {@code local y = 0}，这也是样例层用的
 * 位置。层高于可玩段界，所以 {@code SimulationSection} 的 {@code eligible} 为真，写入不会被丢弃。
 *
 * <p>
 * 生产者只在列被绑定时跑一次，对应原版「段被加载即编译一次」的节奏。
 */
public final class SimulationLayer {

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

    /** 样例层：方块 {@code y = 2^40} 那一层，段内第一层全铺草。 */
    public static SimulationLayer grassPlane() {
        return new SimulationLayer(1L << 36, section -> {
            SimulationBlock block = SimulationBlock.full(Blocks.GRASS_BLOCK.defaultBlockState());
            for (int x = 0; x < SimulationSection.SIZE; x++) {
                for (int z = 0; z < SimulationSection.SIZE; z++) {
                    section.set(x, 0, z, block);
                }
            }
        });
    }
}
