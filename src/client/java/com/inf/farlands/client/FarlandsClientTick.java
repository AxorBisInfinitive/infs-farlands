package com.inf.farlands.client;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.FarlandsTick;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.util.maps.BlockUtil;
import com.inf.farlands.util.maps.SectionUtil;

import net.minecraft.client.Minecraft;

/**
 * 客户端侧信道表的周期回收，成员顺序与职责划分对照服务端那份收尾。
 *
 * 分离 JVM 的客户端没有服务端 tick，两张表只增不减，侧信道的 lastAccess 也一直停在 0：
 * 打戳点读的是 FarlandsTick 的时钟，而那个时钟只在服务端 tick 上推进。所以这里第一件事是把
 * 同一份时钟推进起来，判据便与服务端逐字相同：SectionUtil 走 TTL，BlockUtil 走换代。
 * 时钟只有一份且必须在主源集，打戳点在主源集的 IntSectionPos 与 SectionPosMixin 里，
 * 客户端自己再存一份 lastAccess 只会继续读到 0。
 *
 * 聚合 JVM 里集成服务端已经在维护同一批静态表与同一个时钟，客户端不重复这份活，故先按
 * Minecraft.hasSingleplayerServer 跳过，也不去推进那份时钟。
 *
 * <p>
 * 模拟方块层不在这里驱动，它在 {@code Minecraft.setLevel} 的返回处跑一次，见
 * {@code simulation.MinecraftMixin}。
 */
public class FarlandsClientTick {

    private static final int INTERVAL = 200;

    public static void atEnd(Minecraft minecraft, int tickCount) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (minecraft.hasSingleplayerServer()) {
            return;
        }
        FarlandsTick.setNow(tickCount);
        if (tickCount % INTERVAL == 0) {
            swapBlockLookup();
            trimSectionLookup(tickCount);
        }
    }

    private static void swapBlockLookup() {
        int size = BlockUtil.size();
        if (FarlandsConfig.logBlockLookupSwap) {
            InfsFarlands.LOGGER.info("Swapping BlockUtil.lookup, size: {}", size);
        }
        BlockUtil.swap();
    }

    public static void trimSectionLookup(int tickCount) {
        int beforeSize = SectionUtil.size();
        SectionUtil.trim(tickCount);
        int afterSize = SectionUtil.size();
        if (FarlandsConfig.logSectionLookupTrim) {
            InfsFarlands.LOGGER.info("Trimmed SectionUtil.lookup, before: {}, after: {}", beforeSize, afterSize);
        }
    }
}
