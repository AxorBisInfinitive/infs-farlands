package com.inf.farlands.client.mixin.chat;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.StringUtil;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 聊天输入框的长度上限：命令 2048，聊天仍 256。
 *
 * <p>
 * 原版有两处 256，缺一处都等于没改。第一处是 {@code init} 里的 {@code EditBox.setMaxLength(256)}，逐次插入即截断，
 * 粘贴一条三百多位的坐标命令当场被砍。第二处是 {@code normalizeChatMessage} 经
 * {@code StringUtil.trimChatMessage} 的硬截断，发送前再砍一次，而且命令也走这条。
 *
 * <p>
 * 命令包 {@code ServerboundChatCommandPacket} 用无界的 {@code readUtf} 与 {@code writeUtf}，所以 2048 在协议上可用；
 * 聊天包是 {@code writeUtf(msg, 256)}，超长会在写包时抛 {@code IllegalArgumentException}，所以聊天保持 256：第二处只对
 * 以 {@code /} 开头的串放行，其余照旧截断，线上内容与改前一致。
 *
 * <p>
 * 2048 是定值不是配置项：最长的坐标字面量约三百一十位，一条带四个坐标的命令不到一千四百字符。
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    /** 命令输入的长度上限。 */
    private static final int COMMAND_LIMIT = 2048;

    /** 输入框自身的上限，两种内容共用。 */
    @ModifyArg(method = "init()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/EditBox;setMaxLength(I)V"))
    private int farlands$raiseInputLimit(int original) {
        return COMMAND_LIMIT;
    }

    /** 发送前的截断：命令放行到 2048，聊天维持原判。 */
    @Redirect(method = "normalizeChatMessage(Ljava/lang/String;)Ljava/lang/String;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/StringUtil;trimChatMessage(Ljava/lang/String;)Ljava/lang/String;"))
    private String farlands$keepCommandLong(String message) {
        if (message.startsWith("/")) {
            return message.length() > COMMAND_LIMIT ? message.substring(0, COMMAND_LIMIT) : message;
        }
        return StringUtil.trimChatMessage(message);
    }
}
