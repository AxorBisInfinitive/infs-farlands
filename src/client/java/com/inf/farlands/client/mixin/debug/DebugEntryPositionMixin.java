package com.inf.farlands.client.mixin.debug;

import com.inf.farlands.client.debug.DebugTextWrap;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.util.pos.ExactPosition;
import com.inf.farlands.util.world.WorldBounds;
import com.inf.long2.ints.Int1024;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 坐标条目重排：XYZ、Block、Section 三组都按轴强制分行，各轴一行；区块行改成 section 行，给出该 section 落在哪个
 * fsa 文件。
 *
 * <p>
 * 按轴分行的理由是可读性：极端坐标下一个数字三百多位，三轴挤在一行时换行位置落在哪里不可预期，逐轴分行后每行只
 * 有一个轴，换行只发生在该轴内部。
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
 * 原版 XYZ 行是 {@code %.3f / %.5f / %.3f}，三个实参是 1280 位槽位的 double 投影。{@code 2^60} 处那个投影等于
 * {@code 2^60} 本身，小数位永远是 0，所以三行各自改成槽位精确值的十进制写法，小数位数与原来对齐：x 与 z 三位、
 * y 五位。NaN 与无穷由 {@code Binary1280} 自己给写法，这里不做任何甄别。
 *
 * <p>
 * 绝对界外不能用 blockPosition 的数字：它是 setPosRaw 经 Mth.floor 落过 int 的缓存字段，界外一律饱和到
 * int 两端，真正的坐标与段号在 int 上不可区分。界外时 Block 与 Section 两行的数字改由 {@code cellCoord1024}
 * 给出，段号由 {@code shift(-4)} 算，与 SectionPos.blockToSectionCoord 同为算术右移；文件名三段写 NaN，那一带没有
 * 对应的 fsa 文件。用 1024 位而不是 long：long 版到 {@code 2^63} 就饱和，与面板上方的精确值对不上。界内 Block
 * 行原样，Section 行与改动前逐字相同。
 *
 * <p>
 * 注入方式从 {@code @ModifyArgs} 换成 {@code @Redirect}：拆行改变了元素个数，五参 {@code List.of} 装不下，
 * 只能整个换掉这次调用的返回值。实参在字节码里都是 {@code Object}，这里按原顺序取用，只有被替换的那几行重新拼。
 */
@Mixin(DebugEntryPosition.class)
public class DebugEntryPositionMixin {

    @Redirect(method = "display", at = @At(value = "INVOKE", target = "Ljava/util/List;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"))
    private static List<Object> farlands$positionLines(Object ignoredXyz, Object block, Object ignoredChunk,
            Object facing, Object dimension) {
        Entity entity = Minecraft.getInstance().getCameraEntity();
        ExactPosition frame = (ExactPosition) entity;
        // 界外两行的数字取 1024 位的格号：long 版到 2^63 就饱和，与面板上方的精确值对不上。
        Int1024 x = frame.farlands$cellCoord1024(ExactPosition.POS_X);
        Int1024 y = frame.farlands$cellCoord1024(ExactPosition.POS_Y);
        Int1024 z = frame.farlands$cellCoord1024(ExactPosition.POS_Z);
        List<Object> lines = new ArrayList<>(11);
        DebugTextWrap.add(lines, "X: " + frame.farlands$exactValue(ExactPosition.POS_X).toString(3), "X: ");
        DebugTextWrap.add(lines, "Y: " + frame.farlands$exactValue(ExactPosition.POS_Y).toString(5), "Y: ");
        DebugTextWrap.add(lines, "Z: " + frame.farlands$exactValue(ExactPosition.POS_Z).toString(3), "Z: ");
        if (WorldBounds.inBlockAbsolute(x, y, z)) {
            BlockPos pos = entity.blockPosition();
            int blockX = pos.getX();
            int blockY = pos.getY();
            int blockZ = pos.getZ();
            DebugTextWrap.add(lines, "Block X: " + blockX, "Block X: ");
            DebugTextWrap.add(lines, "Block Y: " + blockY, "Block Y: ");
            DebugTextWrap.add(lines, "Block Z: " + blockZ, "Block Z: ");
            DebugTextWrap.add(lines, "Section X: " + (blockX >> 4), "Section X: ");
            DebugTextWrap.add(lines, "Section Y: " + (blockY >> 4), "Section Y: ");
            // 界内：数字仍取 blockPosition 的 int，文件名按 fsa 的三档分组号算
            DebugTextWrap.add(lines, "Section Z: " + (blockZ >> 4) + " ["
                    + SectionIO.fileName(blockX, blockY, blockZ) + "]", "Section Z: ");
        } else {
            DebugTextWrap.add(lines, "Block X: " + x, "Block X: ");
            DebugTextWrap.add(lines, "Block Y: " + y, "Block Y: ");
            DebugTextWrap.add(lines, "Block Z: " + z, "Block Z: ");
            DebugTextWrap.add(lines, "Section X: " + x.shift(-4), "Section X: ");
            DebugTextWrap.add(lines, "Section Y: " + y.shift(-4), "Section Y: ");
            DebugTextWrap.add(lines, "Section Z: " + z.shift(-4) + " [s.NaN.NaN.NaN.fsa]", "Section Z: ");
        }
        lines.add(facing);
        lines.add(dimension);
        return lines;
    }
}
