package com.inf.farlands.client.mixin.debug;

import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.util.world.WorldBounds;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 坐标条目的区块行改成 section 行，给出该 section 落在哪个 fsa 文件；绝对界外时两行的数字换成真值。
 *
 * <p>
 * 本 mod 的方块与光照持久化落在 fsa，原版那行给出的 r.x.z.mca 不再指向真实的存盘位置。
 *
 * <p>
 * 一个 fsa 文件覆盖 32x32 个 chunk、32 个 section，即 512x512x512 格。文件名里的三段就是这三档的分组号，
 * 所以界内显示的三段取同一口径：chunk 右移 5 即 regionX，sectionY 右移 5 即 yBlock，chunk 右移 5 即
 * regionZ，与括号里的文件名逐段对应，可直接到 fsa 目录对号。
 *
 * <p>
 * 绝对界外不能用 blockPosition 的数字：它是 setPosRaw 经 Mth.floor 落过 int 的缓存字段，界外一律饱和到
 * int 两端，真正的坐标与段号在 int 上不可区分。界外时 Block 与 Section 两行的数字因此改由相机实体的
 * double 现算，段号取 long 右移四位，与 SectionPos.blockToSectionCoord 同为算术右移；文件名三段写 NaN，
 * 那一带没有对应的 fsa 文件。界内 Block 行原样，Section 行与改动前逐字相同。
 *
 * <p>
 * 注入点是 display 里唯一一次五参 List.of。用 @ModifyArgs 而不是两条 @ModifyArg：两行落在同一个节点上，
 * 一处注入拿全部实参，不必验证两条注入器在同一节点上共存。处理体是静态 void，实参在字节码里都是 Object，
 * 回写按 String 处理。
 */
@Mixin(DebugEntryPosition.class)
public class DebugEntryPositionMixin {

    @ModifyArgs(method = "display", at = @At(value = "INVOKE", target = "Ljava/util/List;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"))
    private static void farlands$positionLines(Args args) {
        Entity entity = Minecraft.getInstance().getCameraEntity();
        long x = (long) Math.floor(entity.getX());
        long y = (long) Math.floor(entity.getY());
        long z = (long) Math.floor(entity.getZ());
        if (WorldBounds.inBlockAbsolute(x) && WorldBounds.inBlockAbsolute(y) && WorldBounds.inBlockAbsolute(z)) {
            BlockPos pos = entity.blockPosition();
            // 界内：数字仍取 blockPosition 的 int，文件名按 fsa 的三档分组号算
            args.set(2, String.format(Locale.ROOT, "Section: %d %d %d [%s]",
                    pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4,
                    SectionIO.fileName(pos.getX(), pos.getY(), pos.getZ())));
            return;
        }
        args.set(1, String.format(Locale.ROOT, "Block: %d %d %d", x, y, z));
        args.set(2, String.format(Locale.ROOT, "Section: %d %d %d [%s]",
                x >> 4, y >> 4, z >> 4, "s.NaN.NaN.NaN.fsa"));
    }
}
