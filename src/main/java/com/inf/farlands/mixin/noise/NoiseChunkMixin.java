package com.inf.farlands.mixin.noise;

import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 NoiseChunk 构造时取的整条噪声路由换成构造点的地形系统提供。
 *
 * <p>level 由 TerrainSystemContext 侧信道给出：NoiseChunk 的构造器既没有 level 也没有
 * ChunkAccess，而它的构造过程要走这里，所以只能由构造点在构造前 set、构造后 clear。地形系统从那个 level
 * 上取；{@code noReplace} 为真时两处都不换件，按传入的那一份走。
 *
 * <p>拦 RandomState.router 而不是逐个拦 NoiseRouter 的字段读取：构造器里有五处读它派生出的
 * wrappedRouter，即 preliminarySurfaceLevel、finalDensity 与三个矿脉门，拦这一处全部覆盖，
 * 含水层拿到的 router 实参也随之变成系统的。
 *
 * <p>两处 handler 都保持 static：RandomState.router 是实例方法，Aquifer.create 是 invokestatic，
 * Mixin 的 handler 以静态形式接收 receiver，因此这里不持有实例字段。
 *
 * <p>两处调用点各只有 1 个，均用 javap 在运行时 jar 与编译期 jar 核实过。
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/RandomState;router()Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private static NoiseRouter farlands$replaceRouter(RandomState randomState) {
        NoiseRouter vanilla = randomState.router();
        if (TerrainSystemContext.noReplace()) {
            return vanilla;
        }
        TerrainSystem sys = ((LevelSystems) TerrainSystemContext.get()).terrainSystem();
        return sys instanceof NoiseSystem n ? n.createRouter(vanilla) : vanilla;
    }

    /**
     * aquifer 由当前地形系统提供，null 表示用默认 NoiseBasedAquifer。
     * 全空气系统的实现返回恒 AIR 且非 null 的 aquifer，使 MaterialRuleList 短路。
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"))
    private static Aquifer farlands$replaceAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        if (TerrainSystemContext.noReplace()) {
            return Aquifer.create(chunk, pos, router, random, minY, height, picker);
        }
        TerrainSystem sys = ((LevelSystems) TerrainSystemContext.get()).terrainSystem();
        Aquifer a = sys.createAquifer(chunk, pos, router, random, minY, height, picker);
        return a != null ? a : Aquifer.create(chunk, pos, router, random, minY, height, picker);
    }
}
