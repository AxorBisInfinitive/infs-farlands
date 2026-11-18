package com.inf.farlands.mixin.fix.xyz;

import com.inf.farlands.util.pos.ExactTeleport;
import love.crysh.long2.binaries.binary1280.Binary1280;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 坐标参数解析时顺带记下原文的精确值。
 *
 * <p>
 * 坐标解析的结果是 double，精度在 {@code WorldCoordinate.parseDouble} 那一步就丢了。但那个方法里取过原文，
 * 只用它判「有没有小数点」，原文在 {@code CommandContext} 里也还在：参数节点带着 {@code StringRange}，
 * 切出来就是用户敲的那一段。这里按节点名只认 {@code location}，{@code facingLocation} 与旋转这些不碰。
 *
 * <p>
 * 三个 token 的规则照 {@code WorldCoordinate.parseDouble} 抄：{@code ~} 前缀表示相对，斜杠后那一段是增量；
 * 无小数点、非相对、且是水平轴时补半格，与 {@code Vec3Argument.vec3()} 的 {@code centerCorrect} 一致。
 * 解析不了就什么都不放，传送层因此退回原版行为。
 *
 * <p>
 * 同一次解析写两份：落点那份保持「相对轴是增量」，回显那份把相对轴就地加上命令源的位置。回显的基数取命令源的
 * double 位置，不取源的实体槽位：{@code CommandSourceStack} 的 worldPosition 是一个定住的 Vec3，
 * {@code /execute positioned} 换掉的正是这一份而 getEntity 仍返回执行者，用槽位会把执行者的位置算进回显。
 */
@Mixin(Vec3Argument.class)
public class Vec3ArgumentMixin {

    @Inject(method = "getCoordinates(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)Lnet/minecraft/commands/arguments/coordinates/Coordinates;", at = @At("RETURN"))
    private static void farlands$captureExact(CommandContext<CommandSourceStack> context, String name,
            CallbackInfoReturnable<Coordinates> cir) {
        if (!"location".equals(name)) {
            return;
        }
        String text = farlands$argumentText(context, name);
        if (text == null) {
            return;
        }
        boolean[] relative = new boolean[3];
        Binary1280[] values = farlands$parse(text, relative);
        if (values == null) {
            return;
        }
        ExactTeleport.set(values[0], values[1], values[2]);
        Binary1280[] echo = farlands$echoTargets(context.getSource().getPosition(), values, relative);
        ExactTeleport.setEcho(echo[0], echo[1], echo[2]);
    }

    /** 回显的三轴绝对目标。相对轴加命令源的位置，绝对轴原样。 */
    private static Binary1280[] farlands$echoTargets(Vec3 origin, Binary1280[] values, boolean[] relative) {
        Binary1280[] echo = new Binary1280[3];
        for (int axis = 0; axis < 3; axis++) {
            echo[axis] = relative[axis]
                    ? Binary1280.of(axis == 0 ? origin.x : axis == 1 ? origin.y : origin.z).add(values[axis])
                    : values[axis];
        }
        return echo;
    }

    /** 该参数节点在原始输入里的那一段。 */
    private static String farlands$argumentText(CommandContext<CommandSourceStack> context, String name) {
        String input = context.getInput();
        if (input == null) {
            return null;
        }
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes()) {
            if (name.equals(parsed.getNode().getName())) {
                StringRange range = parsed.getRange();
                return input.substring(range.getStart(), range.getEnd());
            }
        }
        return null;
    }

    /**
     * 三个 token。相对记号存增量，绝对记号按原版补半格。相对与否按轴写进 {@code relative}，回显那一份要用。
     */
    private static Binary1280[] farlands$parse(String text, boolean[] relative) {
        String[] tokens = text.trim().split(" +");
        if (tokens.length != 3) {
            return null;
        }
        Binary1280[] values = new Binary1280[3];
        for (int axis = 0; axis < 3; axis++) {
            String token = tokens[axis];
            relative[axis] = token.startsWith("~");
            String number = relative[axis] ? token.substring(1) : token;
            boolean centered = axis != 1 && !relative[axis] && !number.contains(".");
            try {
                Binary1280 value = number.isEmpty() ? Binary1280.ZERO : Binary1280.of(number);
                values[axis] = centered ? value.add(Binary1280.of(0.5)) : value;
            } catch (RuntimeException malformed) {
                return null;
            }
        }
        return values;
    }
}
