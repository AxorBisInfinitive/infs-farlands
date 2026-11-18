package com.inf.farlands.client.debug;

import java.util.ArrayList;
import java.util.List;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.util.pos.ExactPosition;
import love.crysh.long2.binaries.binary1280.Binary1280;
import love.crysh.long2.binaries.binary256.Binary256;
import love.crysh.long2.ints.Int1024;
import love.crysh.long2.ints.Int256;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 调试界面上的坐标量级：三轴精确坐标、三个浮点类型在该值上的 ULP、该值对三个整数类型的可表示性。
 *
 * <p>
 * 坐标取相机实体的 1280 位槽位，出口用科学计数法，小数位 12。ULP 与整数判定都算在「三轴里绝对值最大的那个」上，
 * 这样一行读数同时回答两件事：这个量级的精度阶梯有多粗，以及它还能不能装进各宽度的整数。
 *
 * <p>
 * 任一轴是 {@code NaN} 时，ULP 三项与整数三项一律写 {@code Unknown}。判据必须先于「取绝对值最大者」：{@code NaN}
 * 的一切比较为假，不先判就会静默选出某个轴，把这条规则架空。
 *
 * <p>
 * 三个 ULP 都是「先窄化再取该类型的一格」：{@code Math.ulp(v.toFloat())}、{@code Math.ulp(v.toDouble())}、
 * {@code v.ulp()}。窄化溢出给无穷，{@code ulp} 也给无穷，与整数一项判溢出同向。
 *
 * <p>
 * 整数判定用精确比较，边界由 {@code Binary1280.of(...)} 精确构造，不经过 {@code toDouble}：大值两侧都会糊成
 * 无穷，{@code toDouble} 之后判不出区别。{@code Int256} 的边界取 {@code Int256.MIN_VALUE} 与
 * {@code Int256.MAX_VALUE}，经 {@code Binary1280.of(Int<?>)} 转换。
 *
 * <p>
 * 平手时先出现的轴胜出，只在 {@code x} 与 {@code -x} 同量级时发生。这一条有实际后果：{@code -2^63} 能装进
 * {@code long} 而 {@code 2^63} 不能，取到哪一个决定 {@code Int64} 那一格写不写 {@code Not overflowed}。
 *
 * <p>
 * 三个 ULP 与三个整数状态按档着色，未知一色，六个标签与坐标行不着色。ULP 恒为正且恒为 2 的幂，所以与 1 的
 * 三态比较就是准确的分档，不需要容差。着色经 {@link DebugEntryColors} 的段标记，由
 * {@link com.inf.farlands.client.mixin.debug.DebugScreenOverlayMixin} 在绘制前拆开。
 *
 * <p>
 * 三行按三个槽位实例的同一性缓存：槽位在位置写入时整体替换，三个引用不变就复用上次的结果。不按坐标值比较，
 * 因为 {@code NaN != NaN} 会让缓存永远失效。槽位尚未写过时 {@code exactValue} 每次新建实例，缓存因此不命中，
 * 那时每帧重算一次，属可接受。
 */
public class PositionUnitsDebugEntry implements DebugScreenEntry {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "position_units");

    /** 小数位口径，坐标与三个 ULP 共用。 */
    private static final int DIGITS = 12;

    private static final String OVERFLOWED = "Overflowed";

    private static final String NOT_OVERFLOWED = "Not overflowed";

    private static final String UNKNOWN = "Unknown";

    /** 上次算好的三行与它对应的三个槽位实例。 */
    private static Binary1280 cachedX;
    private static Binary1280 cachedY;
    private static Binary1280 cachedZ;
    private static List<String> cachedLines = List.of();

    @Override
    public void display(DebugScreenDisplayer displayer, Level serverOrClientLevel, LevelChunk clientChunk,
            LevelChunk serverChunk) {
        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (!(entity instanceof ExactPosition frame)) {
            return;
        }
        Binary1280 x = frame.farlands$exactValue(ExactPosition.POS_X);
        Binary1280 y = frame.farlands$exactValue(ExactPosition.POS_Y);
        Binary1280 z = frame.farlands$exactValue(ExactPosition.POS_Z);
        if (x != cachedX || y != cachedY || z != cachedZ) {
            cachedX = x;
            cachedY = y;
            cachedZ = z;
            cachedLines = layout(x, y, z);
        }
        displayer.addToGroup(ID, cachedLines);
    }

    private static List<String> layout(Binary1280 x, Binary1280 y, Binary1280 z) {
        List<String> lines = new ArrayList<>(4);
        lines.add("X: " + scientific(x) + " / Y: " + scientific(y) + " / Z: " + scientific(z));
        if (x.isNaN() || y.isNaN() || z.isNaN()) {
            String unknown = farlands$unknown();
            lines.add("Float: " + unknown + " / Double: " + unknown);
            lines.add("Binary256: " + unknown + " / Binary1280: " + unknown);
            lines.add("Int32: " + unknown + " / Int64: " + unknown + " / Int256: " + unknown
                    + " / Int1024: " + unknown);
            return lines;
        }
        Binary1280 value = farlands$largest(x, y, z);
        lines.add("Float: " + farlands$ulp(Binary1280.of(Math.ulp(value.toFloat())))
                + " / Double: " + farlands$ulp(Binary1280.of(Math.ulp(value.toDouble()))));
        // Binary256 一列是同值的 256 位表示，给出旧宽度会粗到什么程度；ulp 是 2 的幂，转换无损。
        lines.add("Binary256: " + farlands$ulp(Binary1280.of(Binary256.of(value).ulp()))
                + " / Binary1280: " + farlands$ulp(value.ulp()));
        lines.add("Int32: " + farlands$state(value, Integer.MIN_VALUE, Integer.MAX_VALUE)
                + " / Int64: " + farlands$state(value, Long.MIN_VALUE, Long.MAX_VALUE)
                + " / Int256: " + farlands$state(value, Int256.MIN_VALUE, Int256.MAX_VALUE)
                + " / Int1024: " + farlands$state(value, Int1024.MIN_VALUE, Int1024.MAX_VALUE));
        return lines;
    }

    /**
     * 三轴里绝对值最大的那个坐标的值本身，带符号。平手保留先出现者，只在 {@code x} 与 {@code -x} 同量级时发生。
     */
    private static Binary1280 farlands$largest(Binary1280 x, Binary1280 y, Binary1280 z) {
        Binary1280 largest = x;
        Binary1280 magnitude = Binary1280.abs(x);
        if (Binary1280.abs(y).compareTo(magnitude) > 0) {
            largest = y;
            magnitude = Binary1280.abs(y);
        }
        if (Binary1280.abs(z).compareTo(magnitude) > 0) {
            largest = z;
        }
        return largest;
    }

    /** 该值在该整数类型上是否可表示。边界两侧都用精确值比较。 */
    private static String farlands$state(Binary1280 value, long min, long max) {
        if (value.compareTo(Binary1280.of(max)) > 0 || value.compareTo(Binary1280.of(min)) < 0) {
            return DebugEntryColors.colored(DebugEntryColors.Shade.RISK, OVERFLOWED);
        }
        return DebugEntryColors.colored(DebugEntryColors.Shade.SAFE, NOT_OVERFLOWED);
    }

    /** {@code Int<?>} 一族共用一条：边界值经 {@code of(Int<?>)} 精确转换，比较留在 Binary1280 域内。 */
    private static String farlands$state(Binary1280 value, love.crysh.long2.Int<?> min, love.crysh.long2.Int<?> max) {
        if (value.compareTo(Binary1280.of(max)) > 0 || value.compareTo(Binary1280.of(min)) < 0) {
            return DebugEntryColors.colored(DebugEntryColors.Shade.RISK, OVERFLOWED);
        }
        return DebugEntryColors.colored(DebugEntryColors.Shade.SAFE, NOT_OVERFLOWED);
    }

    /**
     * 一格 ULP 的着色。ULP 恒为正且恒为 2 的幂，与 1 的三态比较因此是准确的分档，不需要容差。
     *
     * <p>
     * 窄化溢出时该格是无穷，比较落在大于 1 这一档，与「精度已经没了」同义。
     */
    private static String farlands$ulp(Binary1280 ulp) {
        int compared = ulp.compareTo(Binary1280.ONE);
        DebugEntryColors.Shade shade = compared < 0 ? DebugEntryColors.Shade.SAFE
                : compared > 0 ? DebugEntryColors.Shade.RISK : DebugEntryColors.Shade.EDGE;
        return DebugEntryColors.colored(shade, scientific(ulp));
    }

    /** 六格共用的未知文本。 */
    private static String farlands$unknown() {
        return DebugEntryColors.colored(DebugEntryColors.Shade.UNKNOWN, UNKNOWN);
    }

    private static String scientific(Binary1280 value) {
        return value.toScientificString(DIGITS);
    }
}
