package com.inf.farlands.mixin.fix.xyz;

import com.inf.long2.binaries.Binary256;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.arguments.coordinates.WorldCoordinate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 坐标字面量的接受范围改成与 {@code Binary256.isValid} 一致。
 *
 * <p>
 * 原版的数字扫描是 Brigadier 的 {@code StringReader.readDouble}，字符集只有数字、{@code .} 与 {@code -}。
 * 所以 {@code /tp @s 2e7 64 100} 在坐标那支读成 {@code 2} 加一个残留的 {@code e}，解析失败，Brigadier 随后
 * 退回 {@code /tp <targets> <destination>} 那一支，把 {@code 2e7} 当实体名。
 *
 * <p>
 * 这里只换 {@code parseDouble} 里那一次读数：先按空格切出整段，再交给 {@code Binary256.isValid} 裁决，通过
 * 就用 {@code Binary256.of} 的舍入结果当 double。于是接受范围与库完全同集，不再是手工维护的字符表。
 *
 * <p>
 * 三处因此与原来不同，都写在库的语法里：
 *
 * <ul>
 * <li>带指数的写法可用，例如 {@code 2e7}、{@code -1.5E9}。
 * <li>符号可以带正号，例如 {@code +2}。
 * <li>{@code NaN} 与 {@code Infinity} 也在集合内。库把这三个串当合法值，一致的代价就是坐标也接受它们；
 * 落点由 {@code BlockPos.containing} 饱和到 int 两端，与越界坐标同一档。
 * <li>只改 {@code parseDouble}。{@code parseInt} 那条，即绝对整数坐标例如 {@code /setblock}，保持原样。
 * </ul>
 * </ul>
 *
 * <p>
 * 非法段抛 {@code ERROR_EXPECTED_DOUBLE}，与原来「读不出数」同一档，光标回到段首。
 */
@Mixin(WorldCoordinate.class)
public class WorldCoordinateSyntaxMixin {

    @Redirect(method = "parseDouble(Lcom/mojang/brigadier/StringReader;Z)Lnet/minecraft/commands/arguments/coordinates/WorldCoordinate;", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;readDouble()D"))
    private static double farlands$readDouble(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        String text = reader.getString().substring(start, reader.getCursor());
        if (!Binary256.isValid(text)) {
            reader.setCursor(start);
            throw WorldCoordinate.ERROR_EXPECTED_DOUBLE.createWithContext(reader);
        }
        return Binary256.of(text).toDouble();
    }
}
