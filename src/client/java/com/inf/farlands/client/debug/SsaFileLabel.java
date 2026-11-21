package com.inf.farlands.client.debug;

import com.inf.farlands.simulation.serialize.SimulationFileIndex;
import com.inf.farlands.simulation.serialize.SimulationSectionIO;
import love.crysh.long2.ints.Int1024;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 模拟之地的 ssa 文件名取值，供坐标调试面板的 Section Z 行使用。
 *
 * <p>
 * 文件名里的编号来自该维度目录下的 {@code mapping.dat}，那份表由服务端独占，类注释写明只在服务端主线程读写。所以
 * 这里不在渲染线程读它：缓存一个不可变条目，键是维度加区域号；键变了就把条目换成隐藏并跳一次服务端线程去查，查回来
 * 再换。渲染线程因此永远只碰 {@link #cache} 这一个引用。
 *
 * <p>
 * 三种情形返回 null 由调用方隐藏：区域还没有编号、多人游戏拿不到世界目录、查询在途。读表抛异常也收成 null，服务端自己
 * 的严格读盘口径不因为一块调试面板而改变。
 */
public final class SsaFileLabel {

    /** 一次查询的缓存。键不同即视为未命中。 */
    private record Entry(ResourceKey<Level> dimension, SimulationFileIndex.Region region, String text) {
    }

    /** 当前缓存。只由渲染线程与服务端线程各自整体替换。 */
    private static volatile Entry cache;

    private SsaFileLabel() {
    }

    /**
     * 该段所在 ssa 文件的显示名，取不到时返回 null。
     *
     * @param dimension 维度键
     * @param sectionX  段的 X 号，不是方块坐标
     * @param sectionY  段的 Y 号
     * @param sectionZ  段的 Z 号
     */
    public static String farlands$label(ResourceKey<Level> dimension, Int1024 sectionX, Int1024 sectionY,
            Int1024 sectionZ) {
        SimulationFileIndex.Region region = SimulationSectionIO.regionOf(sectionX, sectionY, sectionZ);
        Entry cached = cache;
        if (cached != null && cached.dimension().equals(dimension) && cached.region().equals(region)) {
            return cached.text();
        }
        // 键变了：先占住这一格，本帧按隐藏处理，等服务端线程把结果换回来。
        cache = new Entry(dimension, region, null);
        farlands$request(dimension, region);
        return null;
    }

    /** 跳服务端线程查一次，结果连同键一起写回缓存。 */
    private static void farlands$request(ResourceKey<Level> dimension, SimulationFileIndex.Region region) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return;
        }
        server.execute(() -> {
            String text;
            try {
                String id = SimulationFileIndex.of(level).find(region);
                text = id == null ? null : SimulationSectionIO.fileName(id);
            } catch (RuntimeException e) {
                // mapping.dat 读失败是服务端自己的口径，面板这一侧只收起文本。
                text = null;
            }
            cache = new Entry(dimension, region, text);
        });
    }
}
