package com.inf.farlands.client.mixin.debug;

import java.util.List;

import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.components.debug.DebugEntryChunkGeneration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 调试屏的 chunk generation 条目在渲染线程上取该维度的服务端生成器。
 *
 * <p>
 * {@code addDebugScreenInfo} 里那一项 {@code N} 会构造一个 {@code NoiseChunk}，而构造点读
 * {@link TerrainSystemContext} 的侧信道，未设置即抛。渲染线程上没有任何填充路径设过它，所以这一条在
 * 正常坐标下必然抛，异常穿出调试屏的渲染。侧信道只能由持有 level 的调用点交进去。
 *
 * <p>
 * 条目每帧都跑，但只在方块位置变化时进 {@code update}，所以这里每个方块位置变化设一次。写成包裹而不是
 * 头尾两处注入，是为了让异常路径也清掉。
 */
@Mixin(DebugEntryChunkGeneration.class)
public abstract class DebugEntryChunkGenerationMixin {

    @WrapOperation(method = "update(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerLevel;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;addDebugScreenInfo(Ljava/util/List;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/core/BlockPos;)V"))
    private static void farlands$withTerrainSystem(ChunkGenerator generator, List<String> result,
            RandomState randomState, BlockPos feetPos, Operation<Void> original, LevelChunk serverChunk,
            BlockPos cachedFeetPos, ServerLevel level) {
        TerrainSystem system = ((LevelSystems) level).terrainSystem();
        if (system == null) {
            original.call(generator, result, randomState, feetPos);
            return;
        }
        TerrainSystemContext.set(system);
        try {
            original.call(generator, result, randomState, feetPos);
        } finally {
            TerrainSystemContext.clear();
        }
    }
}
