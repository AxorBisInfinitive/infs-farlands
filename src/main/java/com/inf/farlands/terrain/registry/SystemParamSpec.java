package com.inf.farlands.terrain.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.mojang.serialization.Dynamic;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.registry.SystemsData.ArgType;

import net.minecraft.network.chat.Component;

/**
 * 一个系统构造参数的声明项：键、取值类型、可选枚举类、可选字面默认值，以及若干关键字映射。
 *
 * <p>
 * 关键字是一张映射：左端是框里可输入的串，右端是该条的求值器，各带一个语言键指向语言文件里的
 * 注释。左端允许空串，空串代表空框。空框取哪一条不再用单独字段指认，就是左端为 {@code ""} 的那条。
 *
 * <p>
 * 类型是必需信息，单独存，不塞进默认值：默认值可空，null 表示没有字面默认值，那种参数的 Arg 里
 * 就没有类型可读，比如 seed。有默认值时校验它与声明类型一致，避免两处写法不一致而无人发现。
 *
 * <p>
 * 声明走 {@link Builder}：入口 {@link #ofLong(String)} 一类给出键与类型，链上声明映射与默认值，
 * {@link Builder#build()} 收口并校验，产出本类实例。builder 与成品是两个类型，中途态留在 builder 里。
 */
public final class SystemParamSpec {

    private final String key;
    private final ArgType type;
    private final Class<? extends Enum<?>> enumClass;
    private final Arg defaultValue;
    private final Map<String, Keyword> keywords;
    /** 单值语义检查，null 表示没有声明。入参是本条解析出的 Arg，通过返回 null。消息用语言键，随客户端语言走。 */
    private final Function<Arg, Component> check;

    /**
     * 一条关键字：求值器加它自己的语言键。语言键指向语言文件里的注释，两种语言各一条同名条目，
     * 语言由游戏在渲染时选，所以这里只存键，不存文案，也不存语言码。
     */
    public record Keyword(Supplier<Arg> value, String langKey) {

        public Keyword {
            if (value == null) {
                throw new NullPointerException("System param keyword value is null");
            }
            if (langKey == null || langKey.isEmpty()) {
                throw new IllegalArgumentException("System param keyword has no lang key");
            }
        }
    }

    private SystemParamSpec(String key, ArgType type, Class<? extends Enum<?>> enumClass, Arg defaultValue,
            Map<String, Keyword> keywords, Function<Arg, Component> check) {
        this.key = key;
        this.type = type;
        this.enumClass = enumClass;
        this.defaultValue = defaultValue;
        this.keywords = Collections.unmodifiableMap(new LinkedHashMap<>(keywords));
        this.check = check;
    }

    /** 参数名：仅允许 a-zA-Z0-9_-。 */
    static boolean isValidName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return name.length() > 0;
    }

    public String key() {
        return key;
    }

    public ArgType type() {
        return type;
    }

    public Class<? extends Enum<?>> enumClass() {
        return enumClass;
    }

    public Arg defaultValue() {
        return defaultValue;
    }

    /** 左端到关键字的映射，保登记顺序。空串左端就是空框那条。 */
    public Map<String, Keyword> keywords() {
        return keywords;
    }

    /** 声明级单值校验。通过返回 null，否则返回给人看的错因。未声明检查时恒通过。 */
    public Component violation(Arg arg) {
        return this.check == null ? null : this.check.apply(arg);
    }

    /**
     * 默认值的文本形式，没有默认值时为 null。按声明类型分派，与界面预填共用同一份写法。
     */
    public String defaultText() {
        if (this.defaultValue == null) {
            return null;
        }
        return text(this.key, this.type, this.defaultValue.value());
    }

    /**
     * 按声明类型把一个参数值写成界面与解析器共用的文本形式：布尔出 true/false，数值不带类型后缀，
     * 字符串原样。默认值与界面预填都走这里，同一形态只有一份实现。
     */
    public static String text(String key, ArgType type, Dynamic<?> value) {
        return switch (type) {
            case INT, SHORT, BYTE, LONG -> Long.toString(number(key, value).longValue());
            case FLOAT, DOUBLE -> doubleText(number(key, value).doubleValue());
            case BOOLEAN -> number(key, value).longValue() != 0L ? "true" : "false";
            case STRING, ENUM -> string(key, value);
        };
    }

    private static <T> Number number(String key, Dynamic<T> value) {
        return value.getOps().getNumberValue(value.getValue())
                .getOrThrow(message -> new IllegalStateException(
                        "System param is not a number: " + key + " " + message));
    }

    private static <T> String string(String key, Dynamic<T> value) {
        return value.getOps().getStringValue(value.getValue())
                .getOrThrow(message -> new IllegalStateException(
                        "System param is not a string: " + key + " " + message));
    }

    private static String doubleText(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    // ---- 入口：与 Config.setInt/setLong/... 同形，返回 builder ----

    public static IntBuilder ofInt(String key) {
        return new IntBuilder(key);
    }

    public static ShortBuilder ofShort(String key) {
        return new ShortBuilder(key);
    }

    public static ByteBuilder ofByte(String key) {
        return new ByteBuilder(key);
    }

    public static LongBuilder ofLong(String key) {
        return new LongBuilder(key);
    }

    public static FloatBuilder ofFloat(String key) {
        return new FloatBuilder(key);
    }

    public static DoubleBuilder ofDouble(String key) {
        return new DoubleBuilder(key);
    }

    public static BoolBuilder ofBoolean(String key) {
        return new BoolBuilder(key);
    }

    public static StringsBuilder ofString(String key) {
        return new StringsBuilder(key);
    }

    /** 枚举项的类由调用方传入：builder 形态下值稍后才由 define 给出，类不能在入口处从值上取。 */
    public static <T extends Enum<T>> EnumBuilder<T> ofEnum(String key, Class<T> type) {
        return new EnumBuilder<>(key, type);
    }

    // ---- builder ----

    /**
     * 声明收集器。中途态留在本类，{@link #build()} 收口并校验。校验放 build 而不是每次调用链上方法：
     * 链上的中间态不该被当成成品。
     */
    public abstract static class Builder<B extends Builder<B>> {

        protected final String key;
        private final ArgType type;
        private final Class<? extends Enum<?>> enumClass;
        /** 由各类型子类的 define 赋值，故为 protected。 */
        protected Arg defaultValue;
        /** 由各类型子类的 check 赋值，故为 protected。 */
        protected Function<Arg, Component> check;
        private final Map<String, Keyword> keywords = new LinkedHashMap<>();

        Builder(String key, ArgType type, Class<? extends Enum<?>> enumClass) {
            if (!isValidName(key)) {
                throw new IllegalArgumentException("Invalid system param key: " + key);
            }
            this.key = key;
            this.type = type;
            this.enumClass = enumClass;
        }

        abstract B self();

        /**
         * 声明一条映射：左端 key 到求值器 value，并给它挂语言键。左端允许空串。重复左端在此抛，
         * 键的唯一性不依赖后续声明。
         */
        public B keyword(String key, Supplier<Arg> value, String langKey) {
            if (this.keywords.putIfAbsent(key, new Keyword(value, langKey)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate system param keyword: " + this.key + "." + key);
            }
            return self();
        }

        public SystemParamSpec build() {
            if (this.type == ArgType.ENUM && this.enumClass == null) {
                throw new IllegalArgumentException("Enum system param needs an enum class: " + this.key);
            }
            if (this.type != ArgType.ENUM && this.enumClass != null) {
                throw new IllegalArgumentException("Non-enum system param carries an enum class: " + this.key);
            }
            if (this.defaultValue != null && this.defaultValue.type() != this.type) {
                throw new IllegalArgumentException("System param default type mismatch: " + this.key
                        + " declares " + this.type.id() + " but defaults to " + this.defaultValue.type().id());
            }
            return new SystemParamSpec(this.key, this.type, this.enumClass, this.defaultValue, this.keywords,
                    this.check);
        }
    }

    public static final class IntBuilder extends Builder<IntBuilder> {
        IntBuilder(String key) {
            super(key, ArgType.INT, null);
        }

        @Override
        public IntBuilder self() {
            return this;
        }

        public IntBuilder define(int value) {
            this.defaultValue = Arg.ofInt(value);
            return this;
        }

        /** 单值语义检查。通过返回 null，否则返回错因。逐键执行，失败只标红这一个框。 */
        public IntBuilder check(Predicate<Integer> test, Function<Integer, Component> message) {
            this.check = arg -> {
                Integer value = SystemParamSpec.number(this.key, arg.value()).intValue();
                return test.test(value) ? null : message.apply(value);
            };
            return this;
        }

        /** 字面文本的便捷重载。文本不随语言切换，只用于诊断或没有语言文件的第三方系统。 */
        public IntBuilder check(Predicate<Integer> test, String literal) {
            return check(test, value -> Component.literal(literal));
        }
    }

    public static final class ShortBuilder extends Builder<ShortBuilder> {
        ShortBuilder(String key) {
            super(key, ArgType.SHORT, null);
        }

        @Override
        public ShortBuilder self() {
            return this;
        }

        public ShortBuilder define(short value) {
            this.defaultValue = Arg.ofShort(value);
            return this;
        }

        /** 单值语义检查。通过返回 null，否则返回错因。 */
        public ShortBuilder check(Predicate<Short> test, Function<Short, Component> message) {
            this.check = arg -> {
                Short value = SystemParamSpec.number(this.key, arg.value()).shortValue();
                return test.test(value) ? null : message.apply(value);
            };
            return this;
        }

        /** 字面文本的便捷重载。文本不随语言切换，只用于诊断或没有语言文件的第三方系统。 */
        public ShortBuilder check(Predicate<Short> test, String literal) {
            return check(test, value -> Component.literal(literal));
        }
    }

    public static final class ByteBuilder extends Builder<ByteBuilder> {
        ByteBuilder(String key) {
            super(key, ArgType.BYTE, null);
        }

        @Override
        public ByteBuilder self() {
            return this;
        }

        public ByteBuilder define(byte value) {
            this.defaultValue = Arg.ofByte(value);
            return this;
        }

        /** 单值语义检查。通过返回 null，否则返回错因。 */
        public ByteBuilder check(Predicate<Byte> test, Function<Byte, Component> message) {
            this.check = arg -> {
                Byte value = SystemParamSpec.number(this.key, arg.value()).byteValue();
                return test.test(value) ? null : message.apply(value);
            };
            return this;
        }

        /** 字面文本的便捷重载。文本不随语言切换，只用于诊断或没有语言文件的第三方系统。 */
        public ByteBuilder check(Predicate<Byte> test, String literal) {
            return check(test, value -> Component.literal(literal));
        }
    }

    public static final class LongBuilder extends Builder<LongBuilder> {
        LongBuilder(String key) {
            super(key, ArgType.LONG, null);
        }

        @Override
        public LongBuilder self() {
            return this;
        }

        public LongBuilder define(long value) {
            this.defaultValue = Arg.ofLong(value);
            return this;
        }

        /** 单值语义检查。通过返回 null，否则返回错因。 */
        public LongBuilder check(Predicate<Long> test, Function<Long, Component> message) {
            this.check = arg -> {
                Long value = SystemParamSpec.number(this.key, arg.value()).longValue();
                return test.test(value) ? null : message.apply(value);
            };
            return this;
        }

        /** 字面文本的便捷重载。文本不随语言切换，只用于诊断或没有语言文件的第三方系统。 */
        public LongBuilder check(Predicate<Long> test, String literal) {
            return check(test, value -> Component.literal(literal));
        }
    }

    public static final class FloatBuilder extends Builder<FloatBuilder> {
        FloatBuilder(String key) {
            super(key, ArgType.FLOAT, null);
        }

        @Override
        public FloatBuilder self() {
            return this;
        }

        public FloatBuilder define(float value) {
            this.defaultValue = Arg.ofFloat(value);
            return this;
        }

        /** 单值语义检查。通过返回 null，否则返回错因。 */
        public FloatBuilder check(Predicate<Float> test, Function<Float, Component> message) {
            this.check = arg -> {
                Float value = SystemParamSpec.number(this.key, arg.value()).floatValue();
                return test.test(value) ? null : message.apply(value);
            };
            return this;
        }

        /** 字面文本的便捷重载。文本不随语言切换，只用于诊断或没有语言文件的第三方系统。 */
        public FloatBuilder check(Predicate<Float> test, String literal) {
            return check(test, value -> Component.literal(literal));
        }
    }

    public static final class DoubleBuilder extends Builder<DoubleBuilder> {
        DoubleBuilder(String key) {
            super(key, ArgType.DOUBLE, null);
        }

        @Override
        public DoubleBuilder self() {
            return this;
        }

        public DoubleBuilder define(double value) {
            this.defaultValue = Arg.ofDouble(value);
            return this;
        }

        /** 单值语义检查。通过返回 null，否则返回错因。 */
        public DoubleBuilder check(Predicate<Double> test, Function<Double, Component> message) {
            this.check = arg -> {
                Double value = SystemParamSpec.number(this.key, arg.value()).doubleValue();
                return test.test(value) ? null : message.apply(value);
            };
            return this;
        }

        /** 字面文本的便捷重载。文本不随语言切换，只用于诊断或没有语言文件的第三方系统。 */
        public DoubleBuilder check(Predicate<Double> test, String literal) {
            return check(test, value -> Component.literal(literal));
        }
    }

    public static final class BoolBuilder extends Builder<BoolBuilder> {
        BoolBuilder(String key) {
            super(key, ArgType.BOOLEAN, null);
        }

        @Override
        public BoolBuilder self() {
            return this;
        }

        public BoolBuilder define(boolean value) {
            this.defaultValue = Arg.ofBoolean(value);
            return this;
        }
    }

    public static final class StringsBuilder extends Builder<StringsBuilder> {
        StringsBuilder(String key) {
            super(key, ArgType.STRING, null);
        }

        @Override
        public StringsBuilder self() {
            return this;
        }

        public StringsBuilder define(String value) {
            this.defaultValue = Arg.ofString(value);
            return this;
        }
    }

    public static final class EnumBuilder<T extends Enum<T>> extends Builder<EnumBuilder<T>> {
        EnumBuilder(String key, Class<T> enumClass) {
            super(key, ArgType.ENUM, enumClass);
        }

        @Override
        public EnumBuilder<T> self() {
            return this;
        }

        public EnumBuilder<T> define(T value) {
            this.defaultValue = Arg.ofEnum(value);
            return this;
        }
    }
}
