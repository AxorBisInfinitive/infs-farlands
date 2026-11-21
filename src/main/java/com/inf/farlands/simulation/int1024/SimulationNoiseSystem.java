package com.inf.farlands.simulation.int1024;

import com.inf.farlands.simulation.int1024.noise.Density;
import com.inf.farlands.simulation.int1024.noise.Router;

/**
 * 密度链地形系统，对应 terrain 的 {@code NoiseSystem}。
 *
 * <p>方法与默认值照抄：{@code createRouter} 默认只换 finalDensity，{@code noiseSizeXZ} 与 {@code noiseSizeY}
 * 的 0 表示「用设置里的那一份」。
 */
public interface SimulationNoiseSystem extends SimulationTerrainSystem {

    Density createFinalDensity(Router router);

    /**
     * 整条路由。默认只把 finalDensity 换成本系统的，其余字段照抄。
     *
     * <p>
     * 每段可能被调两次：群系那一档要六条气候密度，方块那一档要 {@code finalDensity}，两处各取一次。覆写者按
     * 无副作用写，别在这里记状态或做一次性初始化。
     */
    default Router createRouter(Router vanilla) {
        return vanilla.withFinalDensity(createFinalDensity(vanilla));
    }

    /** 水平 cell 网格，单位 noiseSize，乘 4 得 cell 宽。0 表示用设置里的那一份。 */
    default int noiseSizeXZ() {
        return 0;
    }

    /** 竖直 cell 网格，单位 noiseSize，乘 4 得 cell 高。0 表示用设置里的那一份。 */
    default int noiseSizeY() {
        return 0;
    }
}
