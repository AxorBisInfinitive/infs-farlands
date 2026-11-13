package com.inf.farlands.terrain.system.common.overworld.Vanilla;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 按 seed 取 overworld Vanilla 那一族要用的 {@link RandomState}。
 *
 * <p>
 * 群系、地表、雕刻三族要的随机性都在 RandomState 里：气候采样器、{@code SurfaceSystem}，以及喂给
 * 雕刻上下文与起点随机的件。三族 seed 相同时共用同一份，默认配置下每个 level 只多建一份而不是三份。
 *
 * <p>
 * 必须返回同一个实例：三族从同一条缓存上按 seed 取，重复现建会让每个调用点各持一份，缓存也就失去意义。
 *
 * <p>
 * 键取 server level 的身份，与 {@code SystemsIO.systemsFor} 同形；值不引用 level，弱键可回收。
 * 构造内容是注册表只读加纯对象图，可以在生成线程上建：首次取用发生在 {@code applySurface} 与
 * {@code applyCarvers} 里，缓存取用按外层同步、内层 ConcurrentHashMap 处理。
 */
public final class VanillaFamilyRandom {

    private static final Map<ServerLevel, Map<Long, RandomState>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VanillaFamilyRandom() {
    }

    /**
     * 该 level 上以该 seed 建的 RandomState。三样输入与 {@code ChunkMap} 建该 level 自己那份时
     * 相同：生成器的噪声设置、NOISE 注册表、seed。
     */
    public static RandomState forSeed(ServerLevel level, long seed, NoiseBasedChunkGenerator gen) {
        synchronized (CACHE) {
            Map<Long, RandomState> bySeed = CACHE.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
            return bySeed.computeIfAbsent(seed,
                    k -> RandomState.create(gen.generatorSettings().value(),
                            level.registryAccess().lookupOrThrow(Registries.NOISE), k));
        }
    }
}
