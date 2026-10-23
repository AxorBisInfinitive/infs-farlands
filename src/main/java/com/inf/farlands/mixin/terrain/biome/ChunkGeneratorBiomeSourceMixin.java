package com.inf.farlands.mixin.terrain.biome;

import com.inf.farlands.terrain.biomeSource.BiomeSourceFilter;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 {@code getBiomeSource()} 返回该维度换过的群系源。
 *
 * <p>
 * 换 getter 而不是换字段：字段是 {@code protected final}，且
 * {@code NoiseBasedChunkGenerator.CODEC} 的 {@code forGetter} 读的正是字段。改字段会让序列化看到一份由
 * 数据包内容算出来的源，等于把滤表写进存档；getter 换出不进任何序列化路径。
 *
 * <p>
 * 查表未命中即原样返回，所以客户端、未选该系统的维度、以及没装本模组的场景行为不变。命中时返回的是
 * 登记时那一个实例，不能每次新建：{@code BiomeSource.possibleBiomes()} 是基类 memoize 的，实例一变，
 * 装饰侧按它建的 feature 索引就每次都重建。
 *
 * <p>
 * 目标方法全树唯一、非 final、无重载，javap 打 common jar 核对过签名。
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorBiomeSourceMixin {

    @Inject(method = "getBiomeSource", at = @At("RETURN"), cancellable = true)
    private void farlands$adaptedBiomeSource(CallbackInfoReturnable<BiomeSource> cir) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        BiomeSource raw = cir.getReturnValue();
        BiomeSource adapted = BiomeSourceFilter.lookup(self, raw);
        if (adapted != raw) {
            cir.setReturnValue(adapted);
        }
    }
}
