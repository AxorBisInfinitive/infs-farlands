package com.inf.farlands.simulation.biomeFiller;

import com.inf.farlands.simulation.int1024.SimulationBiomeSystem;
import com.inf.farlands.simulation.int1024.noise.Router;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import love.crysh.long2.ints.Int1024;

import net.minecraft.server.level.ServerLevel;

/**
 * 一段的群系写入：按 4x4x4 的 quart 网格问系统要群系，写进段容器。
 *
 * <p>
 * 单独一档而不是并进方块链：群系的单位成本远低于方块，而方块链失败时群系的成果不该跟着丢。两档的推进与收尾都在
 * {@code SimulationLifecycle}，本类只做纯写入。
 *
 * <p>
 * 空段也写：客户端的群系混合取样会在混合半径内跨段取群系，空段留在 void 上会让贴着它的那一圈方块取到 void 的色。
 *
 * <p>
 * 跑在 {@code farlands-sim} 上，写入走容器的 {@code getAndSetUnchecked}，那一档不取容器自己的线程检测器。
 */
public final class SimulationBiomeFiller {

    /** 一段的 quart 网格边长，即 16 除以 4。 */
    private static final int QUART = SimulationSectionData.SIZE / 4;

    private SimulationBiomeFiller() {
    }

    /** 写满该段的 64 个 quart。quart 原点是段号左移两位，与块坐标的左移四位同源。 */
    public static void fill(ServerLevel level, SimulationSectionKey key, SimulationSectionData data,
            SimulationBiomeSystem system, Router router) {
        Int1024 quartX = key.x().shift(2);
        Int1024 quartY = key.y().shift(2);
        Int1024 quartZ = key.z().shift(2);
        for (int localX = 0; localX < QUART; localX++) {
            Int1024 x = quartX.add(Int1024.of((long) localX));
            for (int localY = 0; localY < QUART; localY++) {
                Int1024 y = quartY.add(Int1024.of((long) localY));
                for (int localZ = 0; localZ < QUART; localZ++) {
                    Int1024 z = quartZ.add(Int1024.of((long) localZ));
                    data.setBiome(localX, localY, localZ, system.computeBiome(level, router, x, y, z));
                }
            }
        }
    }
}
