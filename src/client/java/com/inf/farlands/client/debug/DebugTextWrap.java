package com.inf.farlands.client.debug;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * 调试屏的超宽行按可用宽度拆成多行，续行用标签宽度做悬挂缩进。
 *
 * <p>
 * 可用宽度取半屏减边距。调试屏可能分左右两列，而条目拿不到自己所在列的宽度，这是唯一能保证不压到另一列的口径；
 * 代价是单列时提前换行。半屏这个口径由用户裁定。
 *
 * <p>
 * 断点优先落在斜杠或空格之后，找不到就硬断在任意字符处。坐标串里没有空白，所以绝大多数情况是断在数字中间；这是
 * 换行方案换来的确定行宽，改成科学计数法会丢末几位，与 F3 看真值的用途冲突。
 *
 * <p>
 * 测量用「先按整行宽估算字数，再上下各校一次」的办法，每行只做几次 {@code Font.width}，不逐字符累加。
 */
public final class DebugTextWrap {

    /** 左右各留三像素。 */
    private static final int MARGIN = 6;

    private DebugTextWrap() {
    }

    /**
     * 把该行加入列表，超过可用宽度就拆成多行。
     *
     * @param label 该行的标签，例如 {@code "X: "}，只用来算续行的悬挂缩进
     */
    public static void add(List<Object> lines, String line, String label) {
        Font font = Minecraft.getInstance().font;
        int max = farlands$availableWidth();
        if (font == null || max <= 1 || font.width(line) <= max) {
            lines.add(line);
            return;
        }
        int spaceWidth = Math.max(1, font.width(" "));
        int indentWidth = font.width(label);
        String indent = " ".repeat(Math.max(2, indentWidth / spaceWidth));
        int rest = Math.max(2, max - indentWidth);
        int start = 0;
        boolean first = true;
        while (start < line.length()) {
            int budget = first ? max : rest;
            int end = farlands$fit(font, line, start, budget);
            if (end < line.length()) {
                end = farlands$breakPoint(line, start, end);
            }
            String chunk = line.substring(start, end);
            lines.add(first ? chunk : indent + chunk);
            first = false;
            start = end;
            while (start < line.length() && line.charAt(start) == ' ') {
                start++;
            }
        }
    }

    /** 半屏减左右边距。 */
    private static int farlands$availableWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - MARGIN;
    }

    /** 从 {@code start} 起到能塞进 {@code budget} 像素的最远下标，至少前进一格。 */
    private static int farlands$fit(Font font, String line, int start, int budget) {
        int remaining = line.length() - start;
        int total = font.width(line.substring(start));
        int estimate = (int) ((long) remaining * budget / Math.max(1, total));
        int end = start + Math.min(remaining, Math.max(1, estimate));
        while (end > start + 1 && font.width(line.substring(start, end)) > budget) {
            end--;
        }
        while (end < line.length() && font.width(line.substring(start, end + 1)) <= budget) {
            end++;
        }
        return end;
    }

    /** 在 {@code [start, end)} 的后半段里找最后一个可断处，找不到就返回 {@code end}。 */
    private static int farlands$breakPoint(String line, int start, int end) {
        int floor = start + (end - start) / 2;
        for (int index = end - 1; index > floor; index--) {
            char c = line.charAt(index);
            if (c == '/' || c == ' ') {
                return index + 1;
            }
        }
        return end;
    }
}
