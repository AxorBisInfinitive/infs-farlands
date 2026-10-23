package com.inf.farlands.mixin.terrain.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 取 {@code MultiNoiseBiomeSource} 的参数表。
 *
 * <p>
 * 目标方法私有，只能走访问器或反射。用访问器是因为这条在生成热路径上不出现，但类型与泛型都对得上，
 * 且失败在 APPLY 期就抛，不会静默退化。
 *
 * <p>
 * 直接取它而不是自己读 {@code MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST} 注册表：这个方法已经把两种形态
 * 归一，直接给表的源用自己那份，预设形态的源用 {@code preset.value().parameters()}。所以调用方拿到的一定
 * 是该生成器实际使用的那张表。
 */
@Mixin(MultiNoiseBiomeSource.class)
public interface MultiNoiseBiomeSourceInvoker {

    @Invoker("parameters")
    Climate.ParameterList<Holder<Biome>> farlands$parameters();
}
