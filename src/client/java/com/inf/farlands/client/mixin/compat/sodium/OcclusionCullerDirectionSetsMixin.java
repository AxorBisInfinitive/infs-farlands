package com.inf.farlands.client.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * getDirectionSetsWide 在 int 里算 {@code minBlock?() + 16 * width} 与
 * {@code minBlock?() - 16 * (width - 1)}。相机所在 chunk 是最后一个可玩 chunk 时，
 * {@code minBlockX()} 为 2147483616，加 32 越过 int 上界回绕成 -2147483648，第一处比较因此恒假，
 * X 轴掩码丢掉 0b00001111；section 的 chunkX 比相机小 3 格以上时，第二处的 0b11110000 也不再
 * 置位，掩码整体为 0。掩码为 0 让 joinVisibilityData 返回 0，getConnections 随之返回 0，而
 * visitNeighbors 在 outgoingWide 为 0 处直接 return，同一支的 regular 与 local 路径一并停住，
 * 那一片 section 因此不进任何一棵剔除树。
 *
 * <p>
 * 这里不改原方法体，只在返回处用 long 重算三个掩码并改写返回值。这样原方法已有的注入点不受影响。
 * 前提是原方法无副作用：它只读 viewport 与 section 的 final 字段，不写、不分配，所以先算一次
 * 回绕值再被丢弃不留痕。前提不成立时不能改用这一层，只能换成覆写方法体。
 *
 * <p>
 * 两处比较必须一起 long 化。负侧极值上 {@code minBlock?() - negMargin} 恰好压到 int 下界，一旦
 * 宽度取值变大即刻越界，与正侧的溢出同族。
 */
@Pseudo
@Mixin(OcclusionCuller.class)
public abstract class OcclusionCullerDirectionSetsMixin {

    @Inject(method = "getDirectionSetsWide(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)I", at = @At("RETURN"), cancellable = true)
    private static void farlands$directionSetsInLong(Viewport viewport, RenderSection section, int width,
            CallbackInfoReturnable<Integer> cir) {
        SectionPos origin = viewport.getChunkCoord();

        long minX = origin.minBlockX();
        long minY = origin.minBlockY();
        long minZ = origin.minBlockZ();
        long posMargin = 16L * width;
        long negMargin = 16L * (width - 1);

        int directionSetsX = 0;
        if (minX + posMargin >= section.getOriginX()) {
            directionSetsX = 0b00001111;
        }
        if (minX - negMargin <= section.getOriginX() + 16L) {
            directionSetsX |= 0b11110000;
        }

        int directionSetsZ = 0;
        if (minZ + posMargin >= section.getOriginZ()) {
            directionSetsZ = 0b00110011;
        }
        if (minZ - negMargin <= section.getOriginZ() + 16L) {
            directionSetsZ |= 0b11001100;
        }

        int directionSetsY = 0;
        if (minY + posMargin >= section.getOriginY()) {
            directionSetsY = 0b01010101;
        }
        if (minY - negMargin <= section.getOriginY() + 16L) {
            directionSetsY |= 0b10101010;
        }

        cir.setReturnValue(directionSetsX & directionSetsY & directionSetsZ);
    }
}
