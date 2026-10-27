package com.inf.farlands.mixin.debug;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 调试屏的 noise router 一行改按当前地形系统算。
 *
 * <p>
 * 原版那一行里七项取自 {@code randomState.router()}，即宿主维度的 vanilla router；本 port 的生成用
 * 的是系统自己那份，两者在选了非 vanilla 系统时不同。所以把这一处读取换成系统的 router。
 *
 * <p>
 * 同一行的 {@code N} 由 {@code getInterpolatedNoiseValue} 构造 {@code NoiseChunk} 求得，那个构造点
 * 已经被 {@code NoiseChunkMixin} 接管，只要上下文在位就同样用系统的 router。它的插值网格原本取维度
 * 设置，与生成侧取的系统网格不同，所以第二处把网格也换成系统的，保留维度设置给出的
 * 起止 Y，Y 门不变。
 *
 * <p>
 * 上下文由调用点给：{@code addDebugScreenInfo} 的形参里没有 level，本类这两处都取
 * {@link TerrainSystemContext#getOrNull()}，取不到就原样返回 vanilla 的结果，其它调用方因此不受
 * 影响。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorDebugMixin {

    /**
     * 七项读数改取系统 router。系统不是噪声系统时不换：逐块几何的族不用 router，落回 vanilla 与它的
     * 生成口径一致。
     */
    @Redirect(method = "addDebugScreenInfo(Ljava/util/List;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/core/BlockPos;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/RandomState;router()Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private static NoiseRouter farlands$systemRouter(RandomState randomState) {
        NoiseRouter vanilla = randomState.router();
        TerrainSystem system = TerrainSystemContext.getOrNull();
        return system instanceof NoiseSystem noise ? noise.createRouter(vanilla) : vanilla;
    }

    /**
     * N 的插值网格改取系统的 cell。起止 Y 保留维度设置那一份，Y 门与维度高度因此不变。
     *
     * <p>
     * 系统的两轴与生成侧同一单位，见 {@code AbstractTerrainFiller} 的换算。两轴都是 0 表示该族不自定义
     * 网格，原样返回；只设一轴时另一轴取维度设置的。
     */
    @Redirect(method = "getInterpolatedNoiseValue(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;noiseSettings()Lnet/minecraft/world/level/levelgen/NoiseSettings;"))
    private static NoiseSettings farlands$systemGrid(NoiseGeneratorSettings settings) {
        NoiseSettings vanilla = settings.noiseSettings();
        TerrainSystem system = TerrainSystemContext.getOrNull();
        if (!(system instanceof NoiseSystem noise)) {
            return vanilla;
        }
        int xz = noise.noiseSizeXZ();
        int y = noise.noiseSizeY();
        if (xz == 0 && y == 0) {
            return vanilla;
        }
        return new NoiseSettings(vanilla.minY(), vanilla.height(),
                xz != 0 ? xz : vanilla.noiseSizeHorizontal(),
                y != 0 ? y : vanilla.noiseSizeVertical());
    }
}
