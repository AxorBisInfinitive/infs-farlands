package com.inf.farlands.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 群系系统，决定 section 的 biome 数据来源。
 *
 * 与地形系统正交，biome 是独立阶段，vanilla 的 BIOMES 先于 NOISE。
 * 由 ServerLevel 构造末尾按 id 在 SystemRegistries 里新建，实例随 level 走。
 * 实现必须无状态且纯方法，fillBiomes 跑 genPool 多线程，实例被多个 fill 任务共享。
 */
public interface BiomeSystem {

    /** 填 [minSectionY, maxSectionY] 内每个 section 的 4x4x4 biome 网格。 */
    void fillBiomes(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY);

    /**
     * 把该维度生成器实际使用的群系源换成本系统要的那一份。默认原样返回。
     *
     * <p>
     * 调一次，落在 ServerLevel 构造末尾；返回值按生成器实例登记，此后该生成器的
     * {@code getBiomeSource()} 一律返回它。所以「只换 section 数据」之外的口径，例如雕刻取群系、
     * 结构判群系、刷怪取群系、{@code /locate biome}，也一并跟随，不需要各自再挂判据。
     *
     * <p>
     * 默认实现不换源：不吃群系的系统与不装本模组时逐字相同。返回同一实例表示没有改动。
     */
    default BiomeSource adaptBiomeSource(ServerLevel level, BiomeSource raw) {
        return raw;
    }
}
