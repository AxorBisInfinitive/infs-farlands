package com.inf.long2.ints;

/**
 * 十进制文本到 {@link Int1024} 的严格解析：越界即抛，不回绕。
 *
 * <p>
 * 库里的 {@code Int1024.parseInt} 保留低 1024 位，超出范围的文本静默回绕；{@code isValid} 只查语法，不查范围。
 * 本类补上范围判据。判据落在库自己的解析器上，语法与数值规则因此与 {@code parseInt} 同源，不另造一套。
 *
 * <p>
 * 放在 {@code com.inf.long2.ints} 包里，因为 {@link IntText} 这个类本身与其成员都是包私有。
 *
 * <p>
 * 四步。第一步 {@link IntText#scanDecimal(String)} 判语法，非法即抛。第二步数有效位数，去掉符号与前导零后
 * 超过 {@link Int1024#MAX_VALUE} 的十进制位数即抛；这一步保证第三步的按组解析不会被二次截断，否则
 * {@code 2^1088} 这种输入会整段回绕成零而看起来合法。第三步按 {@code 17} 组解析：低 {@code 16} 组是 1024 位本体，
 * 第 {@code 17} 组必须是第 {@code 16} 组符号位的扩展，否则真实值已经超出一组。第四步把低十六组按大端交给
 * {@link Int1024#of(long[])}。
 *
 * <p>
 * 当前无正式调用方。接线点是模拟层把段号换成 1024 位之后的映射表读盘，那一处今天用 {@code Long.parseLong}，
 * 越界即抛。
 */
public final class Int1024Strict {

    /** 1024 位本体的组数。 */
    private static final int WORDS = 16;

    /** 有符号 1024 位的十进制最大位数，由库常量派生，不硬编码。 */
    private static final int MAX_DECIMAL_DIGITS = Int1024.MAX_VALUE.toString().length();

    private Int1024Strict() {
    }

    /**
     * 把十进制文本解析成 {@link Int1024}，超出 {@code [-2^1023, 2^1023-1]} 即抛。
     *
     * <p>
     * 语法与 {@code Int1024.isValid} 同一套：可选一个 {@code +} 或 {@code -}，至少一位 ASCII 数字，允许前导零，
     * 不接受空白与分隔符。失败一律抛 {@link NumberFormatException}，消息带原串，与 {@code Long.parseLong} 同族，
     * 调用点可以沿用现有的包装写法。
     */
    public static Int1024 parseIntStrict(String text) {
        int start = IntText.scanDecimal(text);
        if (start < 0) {
            throw new NumberFormatException("Invalid decimal value: " + text);
        }
        int digits = 0;
        for (int index = start; index < text.length(); index++) {
            if (digits == 0 && text.charAt(index) == '0') {
                continue;
            }
            digits++;
        }
        if (digits > MAX_DECIMAL_DIGITS) {
            throw new NumberFormatException("Decimal value out of Int1024 range: " + text);
        }
        long[] wide = IntText.parseDecimal(text, WORDS + 1);
        if (wide[WORDS] != wide[WORDS - 1] >> 63) {
            throw new NumberFormatException("Decimal value out of Int1024 range: " + text);
        }
        long[] bigEndian = new long[WORDS];
        for (int index = 0; index < WORDS; index++) {
            bigEndian[index] = wide[WORDS - 1 - index];
        }
        return Int1024.of(bigEndian);
    }
}
