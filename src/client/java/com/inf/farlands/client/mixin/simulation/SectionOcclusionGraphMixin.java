package com.inf.farlands.client.mixin.simulation;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 固定方块所在段在可见性层不算空，纯客户端。
 *
 * <p>
 * 该段的数据层是空的，段包每次落地都会把空标记写回 {@code loadedEmptySections}；而
 * {@code SectionOcclusionGraph.runUpdates} 命中这个集合就既不把段加进八叉树，又把该段网格直接置成
 * {@code EMPTY}。于是段不进可见集，不编译，网格里的方块永远画不出来。
 *
 * <p>
 * 这里改在唯一的判据处，而不是在每个写者后面补摘除：写者有几条，摘除就要补几处，漏一处就复发。本段一律
 * 返回假，走「加进八叉树」那一支，其余段透传集合的真实结果。
 *
 * <p>
 * {@code contains} 在该方法内只有一处调用，见 {@code javap -c} 的偏移 57，整类也只此一处，所以不需要序数；
 * 且方法描述符逐字取自 {@code javap -p -s}。
 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {

    @Redirect(method = "runUpdates(Lnet/minecraft/client/renderer/SectionOcclusionGraph$GraphStorage;Lnet/minecraft/world/phys/Vec3;Ljava/util/Queue;ZLjava/util/function/Consumer;Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;)V", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;contains(J)Z"))
    private boolean farlands$ghostNotEmpty(LongOpenHashSet emptySections, long node) {
        if (!emptySections.contains(node)) {
            return false;
        }
        return !(SectionPos.x(node) == 0 && SectionPos.y(node) == 8 && SectionPos.z(node) == 0);
    }
}
