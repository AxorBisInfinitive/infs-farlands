package com.inf.farlands.terrain.terrainFiller;

/**
 * 地形填充器的唯一实现。逻辑全在 {@link AbstractTerrainFiller}：fill 从 level 现取该维度的
 * TerrainSystem 与 generatorSettings，所以维度不参与分派，任何维度都走这一份。
 *
 * <p>保留成独立类型而不是把基类改成具体类：{@code LevelSystems.terrainFiller()} 需要一个可实例化的类，
 * 而真按维度换填充器时，落点仍在这里。
 */
public final class StandardTerrainFiller extends AbstractTerrainFiller {

    public StandardTerrainFiller() {
    }
}
