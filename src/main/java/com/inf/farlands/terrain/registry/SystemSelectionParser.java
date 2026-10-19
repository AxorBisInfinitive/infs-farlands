package com.inf.farlands.terrain.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.registry.SystemsData.ArgType;
import com.inf.farlands.terrain.registry.SystemsData.SystemSelection;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;

/**
 * 界面参数文本到 {@link Arg} 的反向解析，{@link SystemsIO#args} 的逆过程。
 *
 * <p>输入是创建世界页签里每个参数框的当前文本，按该系统的 {@link SystemDefaultParams} 声明逐项还原。
 * 空框先取该参数左端为空串的关键字，没有该关键字则取声明的字面默认值；两者都没有即视为缺参数，
 * 因为界面为每个参数都建了框，空框只可能是被清空。
 *
 * <p>全部失败硬抛，与 {@link SystemArgs} 的取值方、{@link SystemsIO#read} 同规：错文本不能落到盘上
 * 变成一份看起来正常、实际换了系统的配置。
 */
public final class SystemSelectionParser {

    private SystemSelectionParser() {
    }

    /** 按声明还原一族的选择。系统必须在对应族的注册表里，否则 classOf 抛。 */
    public static SystemSelection parse(SystemId id, Map<String, String> texts, Class<?> systemClass) {
        SystemParams declared = SystemParams.declaredBy(systemClass);
        Map<String, Arg> args = declared == null ? parseFree(texts) : parseDeclared(declared, texts);
        return new SystemSelection(id.value(), args);
    }

    /**
     * 该段文本按声明是否可用：可用返回 null，否则返回可显示的错因。空框按关键字与字面默认值判，
     * 与 {@link #parseDeclared} 的走法一致。
     *
     * <p>
     * 解析成功之后还要过该参数自己声明的单值检查，所以逐键校验同时覆盖类型、量程与语义集合三层，
     * 而跨字段规则不在这里，见 {@link #violations}。
     */
    public static Component problem(SystemParamSpec spec, String text) {
        try {
            Arg arg = text.isEmpty() ? fromEmpty(spec) : parseText(spec, text);
            return spec.violation(arg);
        } catch (RuntimeException e) {
            return Component.literal(message(e));
        }
    }

    /**
     * 一族系统在当前文本下的全部违规，供提交前的那一次检验与界面标红共用。
     *
     * <p>
     * 次序：先逐参数解析，任一失败即只返回解析失败，因为跨字段规则读的是已解析的值；全部解析成功才把值
     * 交给 {@link SystemParams#validate}，由它跑单值检查与跨字段规则。
     *
     * <p>
     * 没有声明时界面是一个自由框，整张 SNBT 一次解析，失败即一条违规，键取空串。
     */
    public static List<SystemParams.Violation> violations(Class<?> systemClass, Map<String, String> texts) {
        SystemParams declared = SystemParams.declaredBy(systemClass);
        if (declared == null) {
            try {
                parseFree(texts);
                return List.of();
            } catch (RuntimeException e) {
                return List.of(new SystemParams.Violation(Set.of(""), Component.literal(message(e))));
            }
        }
        Map<String, Arg> parsed = new LinkedHashMap<>();
        List<SystemParams.Violation> out = new ArrayList<>();
        for (SystemParamSpec spec : declared.entries()) {
            String text = texts.getOrDefault(spec.key(), "");
            try {
                parsed.put(spec.key(), text.isEmpty() ? fromEmpty(spec) : parseText(spec, text));
            } catch (RuntimeException e) {
                out.add(new SystemParams.Violation(Set.of(spec.key()), Component.literal(message(e))));
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        return declared.validate(parsed);
    }

    /** 自由参数框文本是否可用：可用返回 null，否则返回可显示的错因。 */
    public static Component problemFree(String text) {
        try {
            parseFree(Map.of("", text));
            return null;
        } catch (RuntimeException e) {
            return Component.literal(message(e));
        }
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** 有声明：逐项按声明类型解析，空框走关键字或字面默认值。 */
    private static Map<String, Arg> parseDeclared(SystemParams declared, Map<String, String> texts) {
        Map<String, Arg> out = new LinkedHashMap<>();
        for (SystemParamSpec spec : declared.entries()) {
            String text = texts.getOrDefault(spec.key(), "");
            if (text.isEmpty()) {
                out.put(spec.key(), fromEmpty(spec));
                continue;
            }
            out.put(spec.key(), parseText(spec, text));
        }
        return out;
    }

    /**
     * 空框：先看左端为空串的关键字，再退字面默认值。关键字取值器只在走到这里时求值一次，所以
     * 随机种子一类的参数每次创建各得一个值，不会随输入反复变。
     */
    private static Arg fromEmpty(SystemParamSpec spec) {
        SystemParamSpec.Keyword keyword = spec.keywords().get("");
        if (keyword != null) {
            return keyword.value().get();
        }
        Arg fallback = spec.defaultValue();
        if (fallback == null) {
            throw new IllegalStateException(
                    "farlands: system arg " + spec.key() + " has no text, no keyword and no default");
        }
        return fallback;
    }

    /** 无声明：整个框是一张 SNBT 映射，键是参数名，值是自带 type 与 value 的一段 Arg。 */
    private static Map<String, Arg> parseFree(Map<String, String> texts) {
        String text = texts.getOrDefault("", "");
        if (text.isBlank()) {
            return Map.of();
        }
        CompoundTag tag;
        try {
            tag = TagParser.parseCompoundFully(text);
        } catch (Exception e) {
            throw new IllegalStateException("farlands: free-form arg box is not a valid SNBT map", e);
        }
        Map<String, Arg> out = new LinkedHashMap<>();
        for (String key : tag.keySet()) {
            Tag value = tag.get(key);
            out.put(key, Arg.CODEC.parse(NbtOps.INSTANCE, value).getOrThrow(
                    message -> new IllegalStateException(
                            "farlands: free-form arg " + key + " failed to parse: " + message)));
        }
        return out;
    }

    /** 有文本：按声明类型解析。判据是"这个筐里合法的是什么"，不是"这段文本像什么"。 */
    static Arg parseText(SystemParamSpec spec, String text) {
        if (spec.type() == ArgType.ENUM) {
            return enumArg(spec.key(), spec.enumClass(), text);
        }
        return switch (spec.type()) {
            case INT -> Arg.ofInt((int) integral(spec.key(), text, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case LONG -> Arg.ofLong(integral(spec.key(), text, Long.MIN_VALUE, Long.MAX_VALUE));
            case SHORT -> Arg.ofShort((short) integral(spec.key(), text, Short.MIN_VALUE, Short.MAX_VALUE));
            case BYTE -> Arg.ofByte((byte) integral(spec.key(), text, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case FLOAT -> Arg.ofFloat((float) finite(spec.key(), text, Float.MAX_VALUE));
            case DOUBLE -> Arg.ofDouble(finite(spec.key(), text, Double.MAX_VALUE));
            case BOOLEAN -> Arg.ofBoolean(bool(spec.key(), text));
            case STRING -> Arg.ofString(text);
            case ENUM -> throw new IllegalStateException(
                    "farlands: enum arg " + spec.key() + " needs an enum class, plain text is not enough");
        };
    }

    /**
     * 整型文本用 BigDecimal 取精确值，不用 Long.parseLong 加转换：后者对 {@code 12.0} 这类
     * 带小数点的写法直接失败，而配置文件里的数值经 toString 可能带小数点。
     */
    private static long integral(String key, String text, long min, long max) {
        try {
            long value = new java.math.BigDecimal(text.trim()).longValueExact();
            if (value < min || value > max) {
                throw new ArithmeticException();
            }
            return value;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException(
                    "farlands: system arg " + key + " is not an integer in [" + min + ", " + max + "]: " + text, e);
        }
    }

    /** 浮点文本：禁 NaN 与 Infinity，超类型量程即抛，与 ConfigFile 的浮点读法同规。 */
    private static double finite(String key, String text, double limit) {
        double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("farlands: system arg " + key + " is not a number: " + text, e);
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) > limit) {
            throw new IllegalStateException("farlands: system arg " + key + " out of range: " + text);
        }
        return value;
    }

    private static boolean bool(String key, String text) {
        String trimmed = text.trim();
        if ("true".equals(trimmed)) {
            return true;
        }
        if ("false".equals(trimmed)) {
            return false;
        }
        throw new IllegalStateException("farlands: system arg " + key + " is not true or false: " + text);
    }

    /** 枚举文本还原成真实例，并带上枚举类名，取值方 {@link SystemArgs#getEnum} 走 Class.cast 才有东西可取。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static <E extends Enum<E>> Arg enumArg(String key, Class<? extends Enum<?>> enumClass, String text) {
        if (enumClass == null) {
            throw new IllegalStateException("farlands: enum arg " + key + " has no enum class declared");
        }
        try {
            return Arg.<E>ofEnum((E) Enum.valueOf((Class) enumClass, text.trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "farlands: system arg " + key + " has no such enum constant: "
                            + enumClass.getName() + "." + text.trim(), e);
        }
    }
}
