package com.inf.farlands.terrain;

import com.inf.farlands.terrain.decorationFiller.DecorationRegion;
import com.inf.farlands.terrain.structure.StructureDriver;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 装饰系统，决定 chunk 的地物与结构放置来源与应用方式。
 *
 * <p>装饰是独立阶段，CARVERS 之后、光照之前，依赖 fill 与雕刻的产物。由 ServerLevel 构造末尾按
 * id 在 SystemRegistries 里新建，实例随 level 走。实现必须无状态且纯方法，applyDecoration 跑
 * farlands-gen 多线程，实例被多个装饰任务共享。
 *
 * <p>取数只走交进来的 region：它按读域交出主线程备好的 chunk 句柄，池线程不得再查服务端的 chunk
 * 表。越出读域或读域内缺句柄时 region 抛
 * {@link com.inf.farlands.terrain.decorationFiller.DecorationAbort}，那是此刻条件不成立的信号，
 * 由驱动留表重试，不要换成别的异常类型：两者在驱动侧的日志档位与重试语义不同。
 *
 * <p>状态推进、标脏、补发与方块实体装载由 DecorationFiller 负责，本接口只做纯应用。
 */
public interface DecorationSystem {

    /** 对该 chunk 应用全部地物与结构放置。 */
    void applyDecoration(ServerLevel level, LevelChunk center, DecorationRegion region);

    /**
     * 结构起点相。主线程、fill 之前调用，返回本 chunk 是否该继续跑结构相。
     *
     * <p>
     * 默认实现走宿主维度那套基座，与不覆写时逐字相同。自带基座的族覆写它，用自己的群系源、噪声设置、
     * 生成器与 seed 算起点，因此同一个族换到别的维度也产出同一批起点。
     *
     * <p>
     * 返回 false 时调用方按无结构处理：不跑引用相、不等待 ±8 的壳，Beardifier 取空起点表的结果，即
     * {@link net.minecraft.world.level.levelgen.Beardifier#EMPTY}。
     *
     * <p>
     * 覆写若自己算起点，必须夹在 {@code TerrainSystemContext.setVanilla()} 与 {@code clear()} 之间：
     * 起点取 Y 锚会在主线程上构造 {@code NoiseChunk}，那处要求侧信道已设，而哨兵让这次构造不替换
     * router 与 aquifer，高度场因此来自族自己的 settings 与 seed。
     *
     * <p>
     * 本方法跑在主线程，与 {@link #applyDecoration} 的池上执行不同，实现不要在这里读区域或写段。
     */
    default boolean createStructures(ServerLevel level, LevelChunk chunk) {
        StructureDriver.createStructures(level, chunk);
        return true;
    }
}
