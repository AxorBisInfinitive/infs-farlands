package com.inf.farlands.mixin.fix.xyz;

import java.util.Locale;

import com.inf.farlands.util.pos.ExactTeleport;
import com.inf.long2.binaries.binary1280.Binary1280;

import net.minecraft.server.commands.TeleportCommand;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 传送回显的坐标改成读 1280 位真值，记法与调试屏同一套：科学计数法、小数位 12。
 *
 * <p>
 * 只换 {@code formatDouble} 里那一次 {@code String.format}。回显的三个数原本来自 {@code teleportToPos} 的
 * 局部 {@code Vec3}，那一路全是 double：绝对轴的值是 {@code WorldCoordinate} 的 double 分量，相对轴还要再做一次
 * double 加法，两者在 2^60 处都落到 ulp 网格上。真值在同一次解析里已经算好，见 {@code ExactTeleport.echo()}。
 *
 * <p>
 * 轴靠调用顺序判：{@code formatDouble} 只有一个 double 入参，轴信息只存在于调用序列里。两条消息 lambda 各调三次，
 * 顺序都是 x、y、z，所以按 3 取模自对齐。求值是否发生取决于 {@code CommandSourceStack.sendSuccess} 的两个条件，
 * 但一条消息要么三次都发生、要么一次都没有，对齐不会漂。这是本条唯一的前提：vanilla 若改写只印两个坐标，轴会
 * 静默错位。
 *
 * <p>
 * 判据是 Locale 与格式串逐字相等，不命中就原样委托；{@code ExactTeleport.echo()} 为 null 也原样委托，那时回显
 * 仍是 double，与不装本模组相同。
 */
@Mixin(TeleportCommand.class)
public class TeleportEchoMixin {

    /** 小数位口径，与 {@code PositionUnitsDebugEntry} 同值。 */
    @Unique
    private static final int FARLANDS_DIGITS = 12;

    /** 三轴一循环的调用序号。服务端线程独占，不需要同步。 */
    @Unique
    private static int farlands$axis;

    @Redirect(method = "formatDouble(D)Ljava/lang/String;", at = @At(value = "INVOKE",
            target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
    private static String farlands$exactEcho(Locale locale, String format, Object[] args) {
        int axis = farlands$axis;
        farlands$axis = (axis + 1) % 3;
        if (locale == Locale.ROOT && "%f".equals(format)) {
            Binary1280[] echo = ExactTeleport.echo();
            if (echo != null && echo[axis] != null) {
                return echo[axis].toScientificString(FARLANDS_DIGITS);
            }
        }
        return String.format(locale, format, args);
    }
}
