package com.inf.farlands.simulation.int1024;

import com.inf.farlands.simulation.int1024.noise.Router;
import love.crysh.long2.ints.Int1024;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * 模拟层的群系系统，与 terrain 的 {@code BiomeSystem} 同职责。
 *
 * <p>
 * 只有单点问法：批量填由填充器按 4x4x4 的 quart 网格循环调用。模拟层没有 surface 与 carver 那种要读群系的下游，
 * 所以 terrain 那边由 {@code layout} 派生的批量填与 {@code BiomeManager} 这里都不需要。
 *
 * <p>
 * 气候密度不从本族取：六条密度由 noise 族经 {@link Router} 交出，填充器在填一段时把那一份透传进来。这一条与
 * vanilla 的分工同源，{@code Climate.Sampler} 就是那样从 router 的六个字段建起来的。
 *
 * <p>
 * 实现必须无状态且纯方法：实例随 level 走，被 {@code farlands-sim} 上的多个填充任务共享。
 */
public interface SimulationBiomeSystem {

    /**
     * 该 quart 的群系。任意 quart 都成立，包括本段之外的。
     *
     * @param level  该段所属的 level，取注册表与群系源都从它走
     * @param router 本段填充用的那一份噪声路由，六条气候密度从这里读
     */
    Holder<Biome> computeBiome(ServerLevel level, Router router, Int1024 quartX, Int1024 quartY, Int1024 quartZ);
}
