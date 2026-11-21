package com.inf.farlands.simulation.int1024;

import com.inf.farlands.simulation.SimulationLevels;
import com.inf.farlands.simulation.int1024.noise.Router;
import com.inf.farlands.simulation.int1024.noise.instance.Constant;

import net.minecraft.server.level.ServerLevel;

/**
 * 模拟层的噪声路由构造点，方块链与群系步骤共用这一份。
 *
 * <p>
 * 群系与方块分成两档之后，两边各要一份路由：群系要六条气候密度，方块要 {@code finalDensity}。同一个段的两处取到
 * 同一个形状，所以构造点只有这里一处，不各写一份占位。
 *
 * <p>
 * 仿真层没有维度给定的 vanilla 路由，{@link #BASE} 是十五条常量的占位。今天没有任何系统读 {@code finalDensity}
 * 之外的字段；接真实含水层与真实气候时按当时的口径重填这里，而不是在调用点各填一份。
 */
public final class SimulationRouting {

    /** 占位路由：十五个常量，只有 {@code finalDensity} 会被系统换掉。 */
    public static final Router BASE = new Router(
            new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0),
            new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0),
            new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0), new Constant(0.0));

    private SimulationRouting() {
    }

    /**
     * 该 level 的噪声系统。
     *
     * <p>
     * 取用点拿到的类型是基接口 {@link SimulationTerrainSystem}，与 terrain 层 {@code LevelSystems.terrainSystem()}
     * 的形状一致。登记面只收噪声一族，所以这里是全仓唯一把基接口收窄到 {@link SimulationNoiseSystem} 的地方：
     * 将来加逐块或逐段范式时，补在填充器的范式分派与这里各一处，缺一处就会在这里响亮失败，不会静默跑错范式。
     */
    public static SimulationNoiseSystem noiseSystem(ServerLevel level) {
        SimulationTerrainSystem system = ((SimulationLevels) level).simulationTerrainSystem();
        if (system instanceof SimulationNoiseSystem noise) {
            return noise;
        }
        throw new IllegalStateException(
                "farlands: simulation pipeline has no path for " + system.getClass().getName());
    }

    /**
     * 该 level 的噪声系统给出的整条路由。
     *
     * <p>
     * 每段可能被取两次，群系一次、方块一次。这就是 {@code createRouter} 的调用约定，覆写它的系统按无副作用写。
     */
    public static Router router(ServerLevel level) {
        return noiseSystem(level).createRouter(BASE);
    }
}
