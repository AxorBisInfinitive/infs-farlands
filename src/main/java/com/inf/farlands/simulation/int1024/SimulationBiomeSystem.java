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
 * 群系在管线里是上游，不是叶子。先 BIOME 后 NOISE 的档序出自这个方向：NOISE 可以依赖 BIOME，逐段范式通过
 * {@code SimulationSectionContext.biomeAt} 读群系的格值，反过来没有这条依赖。密度链现在读不到，因为本族的接口
 * 面只给 {@code Router} 与 {@code Settings}，拿不到段容器。
 *
 * <p>
 * 本族不给 {@code layout} 与 {@code BiomeManager}：模拟层没有 surface 与 carver 这两个消费者，群系取值也只有段
 * 容器里那一格一条路，没有按源重算的需要。
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
