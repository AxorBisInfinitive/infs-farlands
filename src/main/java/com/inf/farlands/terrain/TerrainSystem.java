package com.inf.farlands.terrain;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 地形系统基接口，三类互斥的填充范式共享的部分，生命周期回调加流体与 aquifer 提供。
 *
 * 三个互斥子接口，一个实现类只实现其一，分派顺序 instanceof BlockSystem、再 instanceof SectionSystem、
 * 最后走密度链：NoiseSystem 走密度链，BlockSystem 逐块几何，SectionSystem 逐段组织。
 *
 * 线程模型：createFluidPicker 与 createAquifer 在
 * NoiseChunk 构造时调用，跑 genPool 或主线程。后两种范式不建 NoiseChunk，这两个方法对它们没有调用点。
 * onChunkFillStart 当前无调用方，默认空实现。
 */
public interface TerrainSystem {

    /**
     * 管线 fill 的 fluidPicker。null 表示用管线默认，即 y &lt; -54 为 lava，其余为 seaLevel 流体。
     * 全空气系统必须覆盖为空气 picker，否则 density 恒负时 aquifer 会按 fluidPicker 填出全水世界。
     */
    default Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        return null;
    }

    /**
     * 管线 fill 的 aquifer。null 表示用 vanilla NoiseBasedAquifer。
     * 全空气系统覆盖为恒 AIR 且非 null，使 MaterialRuleList 短路，矿脉规则不执行。
     */
    default Aquifer createAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        return null;
    }

    /**
     * 密度为正、且 aquifer 与矿脉都没有接管时的落点。默认取维度设置的方块。
     *
     * <p>
     * 这是 fill 全流程唯一读 default_block 的地方：NoiseChunk 本身不碰它，只读噪声设置、aquifer 开关与
     * 矿脉开关。自带方块口径的系统覆写本方法，否则选到别的维度会沿用那个维度的默认方块。
     */
    default BlockState defaultBlock(NoiseGeneratorSettings settings) {
        return settings.defaultBlock();
    }

    /** 每次 fill 开始时的 chunk 上下文回调。当前无调用方。 */
    default void onChunkFillStart(ChunkAccess chunk) {
    }
}
