package com.inf.farlands.client.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试屏文本的分段着色：四个色值与一套段标记。
 *
 * <p>
 * 调试屏只接受 String，绘制走 {@code §} 传统码，因此拿不到任意 RGB：原版解析器只认 {@code ChatFormatting}
 * 那 22 个码，没有十六进制写法。所以这里用一对控制字符把要着色的段包起来，由
 * {@code com.inf.farlands.client.mixin.debug.DebugScreenOverlayMixin} 在绘制前拆开，逐段用 Component 的
 * 样式色画。
 *
 * <p>
 * 段标记形如 {@code §<档位>} 文本 {@code §<收段>}。{@code §} 后面跟的是 {@code ChatFormatting} 里没有的码，
 * {@code StringDecomposer} 对未知码是「两个字符一起跳过、不改样式、不产出字形」，所以标记对
 * {@code Font.width} 的贡献是零，背景条宽度与右列起点都不受影响。用控制字符而不是字母，是因为字母将来
 * 可能被原版占用。标记只由本类产出，本模组独占这套编码。
 *
 * <p>
 * 带标记的行里不要再写传统 {@code §} 码：拆开之后每段是 Component 的 literal，传统码不再被解析。
 */
public final class DebugEntryColors {

    /** 段用默认色，即调用方传给绘制方法的颜色。 */
    public static final int DEFAULT = -1;

    /** 传统格式码的前导字符。 */
    private static final char CODE = '\u00A7';

    /** 收段码，写在每个着色段的末尾。 */
    private static final char END = '\u0000';

    /** 四档含义与颜色。 */
    public enum Shade {

        /** 精度充足：ULP 小于 1，以及 Not overflowed。 */
        SAFE(0xFF8CC265, '\u0001'),

        /** 临界：ULP 等于 1。 */
        EDGE(0xFFD18F52, '\u0002'),

        /** 精度不足：ULP 大于 1，以及 Overflowed。 */
        RISK(0xFFE77680, '\u0003'),

        /** 未知。 */
        UNKNOWN(0xFF42B3C2, '\u0004');

        private final int rgb;

        private final char marker;

        Shade(int rgb, char marker) {
            this.rgb = rgb;
            this.marker = marker;
        }

        public int rgb() {
            return this.rgb;
        }
    }

    /** 一段：颜色、在原始行里的起点、可见文本。rgb 为 {@link #DEFAULT} 表示不着色。 */
    public record Segment(int rgb, int rawStart, String text) {
    }

    private DebugEntryColors() {
    }

    /** 把一个段着色。text 里不应含 {@code §}。 */
    public static String colored(Shade shade, String text) {
        return new StringBuilder(text.length() + 4)
                .append(CODE).append(shade.marker).append(text).append(CODE).append(END).toString();
    }

    /**
     * 按标记拆行。没有标记时返回单段，rgb 为 {@link #DEFAULT}，调用方据此走原样绘制。
     *
     * <p>
     * 收段码之后的文本回到默认色。开段码没有配对时，该段一直延伸到行末。
     */
    public static List<Segment> split(String line) {
        if (line.indexOf(CODE) < 0) {
            return List.of(new Segment(DEFAULT, 0, line));
        }
        List<Segment> segments = new ArrayList<>(4);
        int start = 0;
        int openRgb = DEFAULT;
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) != CODE || i + 1 >= line.length()) {
                i++;
                continue;
            }
            char code = line.charAt(i + 1);
            if (code == END) {
                if (i > start) {
                    segments.add(new Segment(openRgb, start, line.substring(start, i)));
                }
                openRgb = DEFAULT;
                i += 2;
                start = i;
                continue;
            }
            Shade shade = shadeOf(code);
            if (shade == null) {
                i++;
                continue;
            }
            if (i > start) {
                segments.add(new Segment(openRgb, start, line.substring(start, i)));
            }
            openRgb = shade.rgb;
            i += 2;
            start = i;
        }
        if (start < line.length()) {
            segments.add(new Segment(openRgb, start, line.substring(start)));
        }
        return segments;
    }

    /** 标记码到档位，不是本类的标记返回 null。 */
    private static Shade shadeOf(char code) {
        for (Shade shade : Shade.values()) {
            if (shade.marker == code) {
                return shade;
            }
        }
        return null;
    }
}
