package com.inf.farlands.client.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.terrain.registry.SystemsData;

import net.minecraft.resources.Identifier;

/**
 * 已收到的各维度模拟层系统选择，键是维度 id。
 *
 * <p>
 * 只进不出：调试条目按当前维度查它，查不到就显示未收到，不做任何本地推断。键取维度而不是只留最后一包，
 * 跨维度在途的旧包因此不会顶掉当前维度的显示。
 */
public final class ClientSimulationSystems {

    private ClientSimulationSystems() {
    }

    private static final Map<Identifier, SystemsData.SystemSelection> BY_DIMENSION = new ConcurrentHashMap<>();

    public static void put(Identifier dimension, SystemsData.SystemSelection selection) {
        BY_DIMENSION.put(dimension, selection);
    }

    public static SystemsData.SystemSelection get(Identifier dimension) {
        return BY_DIMENSION.get(dimension);
    }
}
