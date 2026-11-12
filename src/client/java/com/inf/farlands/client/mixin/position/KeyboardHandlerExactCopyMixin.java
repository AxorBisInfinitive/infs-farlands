package com.inf.farlands.client.mixin.position;

import java.util.Locale;

import com.inf.farlands.util.pos.ExactPosition;

import com.inf.long2.binaries.Binary256;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * F3+C 复制的坐标改成读 Binary256。
 *
 * <p>
 * 原版那一句 {@code String.format} 吃的是玩家的 {@code getX}、{@code getY}、{@code getZ} 的 double 投影：本 port 的位置
 * 真值在 {@link ExactPosition} 的槽位里，double 只是投影，2^53 之上两位小数不再是位置本身。改写点选在这唯一一次
 * {@code String.format} 调用，因为 {@code handleDebugKeys} 里只有它一处，三个坐标与整条命令都在它的实参里。
 *
 * <p>
 * 判据是格式串逐字相等：原版改了那句格式串，本类自动退回不接管，不猜语义。小数位与原版一致，都是两位。
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerExactCopyMixin {

    @Redirect(method = "handleDebugKeys", at = @At(value = "INVOKE",
            target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
    private static String farlands$exactCopyLocation(Locale locale, String format, Object[] args) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !"/execute in %s run tp @s %.2f %.2f %.2f %.2f %.2f".equals(format)) {
            return String.format(locale, format, args);
        }
        ExactPosition holder = (ExactPosition) player;
        Binary256 x = holder.farlands$exactValue(ExactPosition.POS_X);
        Binary256 y = holder.farlands$exactValue(ExactPosition.POS_Y);
        Binary256 z = holder.farlands$exactValue(ExactPosition.POS_Z);
        return String.format(Locale.ROOT, "/execute in %s run tp @s %s %s %s %.2f %.2f", args[0],
                x.toString(2), y.toString(2), z.toString(2), args[4], args[5]);
    }
}
