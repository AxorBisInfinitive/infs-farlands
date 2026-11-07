package com.inf.farlands.simulation.int64.terrainFiller;

/**
 * 段填充器的唯一实现。
 *
 * <p>逻辑全在 {@link AbstractSimulationFiller}：{@code fill} 现取当前系统与设置，所以维度与系统都不参与分派，
 * 任何段都走这一份。保留成独立类型而不是把基类改成具体类，与 terrain 的 {@code StandardTerrainFiller} 同理：
 * 真按系统或维度换填充器时，落点仍在这里。
 */
public final class StandardSimulationFiller extends AbstractSimulationFiller {

    public StandardSimulationFiller() {
    }
}
