package com.inf.farlands.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * 地表系统，决定 chunk 的地表规则来源与应用方式。
 *
 * surface 是独立阶段，TERRAIN 之后、光照之前，依赖 fill 产出的高度图。
 * 由 ServerLevel 构造末尾按 id 在 SystemRegistries 里新建，实例随 level 走。
 * 实现必须无状态且纯方法，applySurface 跑 genPool 多线程，实例被多个 surface 任务共享。
 */
public interface SurfaceSystem {

    /** 把地表规则应用到该 chunk。状态推进与标脏由 SurfaceFiller 负责，本接口只做纯应用。 */
    void applySurface(ServerLevel level, ChunkAccess chunk);

    /**
     * 本系统交给 {@code SurfaceSystem.buildSurface} 的地表规则。每个 chunk 取一次。
     *
     * <p>
     * 默认返回维度自己的那条，与不覆写时逐字相同。覆写即接管整条规则：数据包对该维度
     * {@code surface_rule} 的改动从此不生效。这是替代系统的语义，与 noise 族的 {@code createRouter}、
     * biome 族的 {@code adaptBiomeSource} 同形。
     *
     * <p>
     * 不得返回 null：调用点直接把它交给 {@code ruleSource.apply(context)}，那里不做判空。
     */
    default SurfaceRules.RuleSource surfaceRule(NoiseGeneratorSettings settings) {
        return settings.surfaceRule();
    }
}
