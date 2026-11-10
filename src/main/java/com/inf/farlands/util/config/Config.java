package com.inf.farlands.util.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 一份配置文件的条目集合：一个实例持有一份文件与它自己的条目表，逐项用 builder 声明。声明形状：
 *
 * <pre>{@code
 * private static final Config CONFIG = new Config("infs-farlands", Map.of(...));
 *
 * public static final ConfigEntry<Integer> MAX_CAP_ITER = CONFIG.setInt("maxCapIter")
 *         .comment("en_us", "Iteration/search cap for extreme-Y loops.")
 *         .comment("zh_cn", "极端 Y 循环的迭代/搜索上限。")
 *         .range(1, 65536)
 *         .define(512)
 *         .build();
 * }</pre>
 *
 * <p>
 * 值的来源在 value/default 位置上二选一：显式字面量，或由
 * {@link Builder#keyword(String, Supplier)} 声明的关键字。命中关键字时由该关键字的取值器
 * 在<b>读盘期求值一次</b>。判据是按该条目声明的关键字反查，因此没声明过的字面量不会被截走。
 *
 * <p>
 * {@link Builder#define(Object)} 是 {@code T} 的重载，所以字面量默认值有静态类型检查；
 * {@link Builder#define(String)} 接受字面量或关键字名，名字必须是已声明的关键字。
 * 方法名首字母必须大写：{@code default} 是 Java 关键字，不能做方法名。
 *
 * <p>
 * 值域由 {@code min} 与 {@code max} 声明，只作用于显式声明的值，见 {@link ConfigEntry.Constraint}。
 *
 * <p>
 * 一次性语义：{@link #init()} 是每个实例的唯一读盘点，读一次、解析一次、约束一次。
 * 本系统<b>不支持热重载</b>，而且是结构性的：一批配置项在类加载期就被内联进字节码，即
 * {@code mixin/expand/xz/border/*} 的 {@code @ModifyConstant}；另一批只在构造时读一次，即
 * 线程池规模、{@code ViewArea.sections} 的尺寸、{@code LinkedHashMap} 的初始容量。
 * 重载只会产出「一部分生效、一部分永远不生效」的状态，而那条分界线既不在配置文件里也不在日志里。
 */
public final class Config {

    /** 该实例的文件路径，由 filePath 经 {@link #resolve(String)} 解析，见构造器。 */
    private final Path file;

    /** 该文件顶层的 note，落盘时写进根对象的 note 键。 */
    private final Map<String, String> fileNotes;

    /** 本文件的条目表，保声明顺序。 */
    private final Map<String, ConfigEntry<?>> entries = new LinkedHashMap<>();

    /** 幂等标志：init() 只读一次本实例的文件；持有它的类重复调用无害。 */
    private boolean initialized;

    /**
     * 文件路径相对 {@code ${user.dir}/config} 解释，永远追加 {@code .json}。
     *
     * <p>
     * 空串、绝对路径、归一化后越出 config 目录，都在这里抛：越出的路径若拖到写盘期，
     * {@code Files.createDirectories} 会在 config 之外建目录。
     */
    public Config(String filePath, Map<String, String> fileNotes) {
        this.file = resolve(filePath);
        this.fileNotes = Map.copyOf(fileNotes);
    }

    /** 解析 filePath 并夹在 config 目录内，判据见构造器。 */
    private static Path resolve(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("farlands: config path must not be blank");
        }
        Path raw = Path.of(filePath);
        if (raw.isAbsolute() || raw.getRoot() != null) {
            throw new IllegalArgumentException("farlands: config path must be relative: " + filePath);
        }
        Path base = Path.of(System.getProperty("user.dir"), "config").normalize();
        Path resolved = base.resolve(filePath + ".json").normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                    "farlands: config path escapes the config directory: " + filePath + " -> " + resolved);
        }
        return resolved;
    }

    // ---- 工厂：9 种值类型 ----

    public IntBuilder setInt(String name) {
        return new IntBuilder(this, name);
    }

    public LongBuilder setLong(String name) {
        return new LongBuilder(this, name);
    }

    public ShortBuilder setShort(String name) {
        return new ShortBuilder(this, name);
    }

    public ByteBuilder setByte(String name) {
        return new ByteBuilder(this, name);
    }

    public FloatBuilder setFloat(String name) {
        return new FloatBuilder(this, name);
    }

    public DoubleBuilder setDouble(String name) {
        return new DoubleBuilder(this, name);
    }

    public BoolBuilder setBoolean(String name) {
        return new BoolBuilder(this, name);
    }

    /** 类名不叫 StringBuilder：那会遮蔽 {@link java.lang.StringBuilder}。 */
    public StringsBuilder setString(String name) {
        return new StringsBuilder(this, name);
    }

    public <T extends Enum<T>> EnumBuilder<T> setEnum(String name, Class<T> type) {
        return new EnumBuilder<>(this, name, type);
    }

    // ---- builder ----

    public abstract static class Builder<T, B extends Builder<T, B>> {

        /** 产出本 builder 的配置实例，build() 把条目登记进它的表。 */
        private final Config owner;

        protected final String name;
        protected final Map<String, String> notes = new LinkedHashMap<>();
        protected T defaultValue;
        protected final Map<String, ConfigEntry.Keyword<T>> keywords = new LinkedHashMap<>();
        /** 默认值命中关键字时记其名字，未命中为 null。 */
        protected String defaultKeyword;
        /** 值域边界各自独立，可只声明一侧。非数值项恒为 null。 */
        protected T min;
        protected T max;
        protected ConfigEntry.Constraint<T> constraint;

        Builder(Config owner, String name) {
            if (!ConfigEntry.isValidName(name)) {
                throw new IllegalArgumentException("Invalid config entry name: %s".formatted(name));
            }
            this.owner = owner;
            this.name = name;
        }

        public abstract B self();

        protected abstract Class<?> type();

        public B comment(String source, String text) {
            notes.put(source, text);
            return self();
        }

        /**
         * 声明一个关键字及其取值器。关键字名不得重复。取值器在读盘期命中该名字时求值一次。
         *
         * <p>
         * 它的注释用 {@link #commentKeyword(String, String, String)} 追加，与
         * {@link #comment(String, String)} 同形。两者分开，是因为条目自己的注释与某个关键字的注释
         * 是两处不同的落点。
         */
        public final B keyword(String kwd, Supplier<T> supplier) {
            if (keywords.containsKey(kwd)) {
                throw new IllegalArgumentException(
                        "Config entry '%s': duplicate keyword '%s'".formatted(name, kwd));
            }
            keywords.put(kwd, new ConfigEntry.Keyword<>(supplier, new LinkedHashMap<>()));
            return self();
        }

        /**
         * 给指定关键字追加一条注释，落在 {@code allow.keyword} 该元素的 {@code note} 上。
         * 与 {@link #comment(String, String)} 同形，只是落点不同；按名字定位，不依赖声明顺序。
         */
        public B commentKeyword(String kwd, String source, String text) {
            ConfigEntry.Keyword<T> kw = keywords.get(kwd);
            if (kw == null) {
                throw new IllegalArgumentException(
                        "Config entry '%s': commentKeyword references undeclared keyword '%s'".formatted(name, kwd));
            }
            kw.notes().put(source, text);
            return self();
        }

        /**
         * 默认值。字面量形式，{@code T} 由各 builder 的类型参数给出，因此取值有静态类型检查。
         *
         * <p>
         * 不做 {@code define(String)} 的重载：{@code T} 为 {@code String} 时两者擦除后签名
         * 相同，编译期就冲突。String 项改由覆写本方法承担。
         */
        public B define(T value) {
            if (value instanceof String token) {
                ConfigEntry.Keyword<T> kw = keywords.get(token);
                if (kw != null) {
                    this.defaultValue = kw.supplier().get();
                    this.defaultKeyword = token;
                    return self();
                }
                throw new IllegalArgumentException(
                        "Config entry '%s': define(\"%s\") is neither a declared keyword nor a literal of this type"
                                .formatted(name, token));
            }
            this.defaultValue = value;
            this.defaultKeyword = null;
            return self();
        }

        public ConfigEntry<T> build() {
            if (owner.entries.containsKey(name)) {
                throw new IllegalStateException("Duplicate config entry: %s".formatted(name));
            }
            if (defaultValue == null && defaultKeyword == null) {
                throw new IllegalStateException("Config entry '%s': missing define(...)".formatted(name));
            }
            if (defaultKeyword == null && constraint != null) {
                // 默认值自身也要合法，否则一份新生成的配置就是坏的。
                // 关键字默认值不校验：它由代码算出，不是用户输入。
                constraint.check(defaultValue);
            }
            ConfigEntry<T> e = new ConfigEntry<>(name, notes, type(), defaultValue, defaultKeyword,
                    keywords, min, max, constraint);
            owner.entries.put(name, e);
            return e;
        }
    }

    /**
     * 数值类型的公共基类。边界方法只在这里出现——非数值类型没有值域，也就没有这些方法。
     * 用 {@code T extends Number & Comparable<T>} 而不是 {@code Comparable<?>} 字段：
     * 后者在共享基类里比较两个通配符参数会撞 wildcard capture。
     *
     * <p>
     * 两侧各自独立，可只声明一侧，也就只校验那一侧。{@code range} 保留为一次声明两侧的糖。
     */
    public abstract static class NumericBuilder<T extends Number & Comparable<T>, B extends NumericBuilder<T, B>>
            extends Builder<T, B> {

        NumericBuilder(Config owner, String name) {
            super(owner, name);
        }

        /** 下界。与 {@link #max} 无关，可单独声明。 */
        public B min(T value) {
            checkBoundary(value);
            this.min = value;
            this.constraint = mergeConstraint(this.constraint, added -> {
                if (added.compareTo(value) < 0) {
                    throw new IllegalArgumentException("value %s below min %s".formatted(added, value));
                }
            });
            return self();
        }

        /** 上界。与 {@link #min} 无关，可单独声明。 */
        public B max(T value) {
            checkBoundary(value);
            this.max = value;
            this.constraint = mergeConstraint(this.constraint, added -> {
                if (added.compareTo(value) > 0) {
                    throw new IllegalArgumentException("value %s above max %s".formatted(added, value));
                }
            });
            return self();
        }

        /** 一次声明两侧，等价于 {@code min(from).max(to)}。 */
        public B range(T from, T to) {
            checkBoundary(from);
            checkBoundary(to);
            if (from.compareTo(to) > 0) {
                throw new IllegalArgumentException(
                        "Config entry '%s': range min > max (%s > %s)".formatted(name, from, to));
            }
            return min(from).max(to);
        }

        /**
         * 关键字名形式的默认值。这里而不是基类声明：基类的 {@code define(T)} 在 {@code T} 为
         * {@code String} 时与本方法擦除后签名相同，放基类编译不过。关键字项的取值类型都是数值，
         * 因此只需要这一个子类承载它。
         */
        public B define(String token) {
            ConfigEntry.Keyword<T> kw = keywords.get(token);
            if (kw == null) {
                throw new IllegalArgumentException(
                        "Config entry '%s': define(\"%s\") is not a declared keyword".formatted(name, token));
            }
            this.defaultValue = kw.supplier().get();
            this.defaultKeyword = token;
            return self();
        }

        /**
         * 复合约束。每个边界各自一条，因此 {@link #range} 可以走
         * {@link #min} 与 {@link #max}：两条判定串联，不存在相互覆盖。
         */
        private ConfigEntry.Constraint<T> mergeConstraint(ConfigEntry.Constraint<T> base,
                ConfigEntry.Constraint<T> added) {
            if (base == null) {
                return added;
            }
            return value -> {
                base.check(value);
                added.check(value);
            };
        }

        /**
         * 边界值不得为 NaN。{@code compareTo} 对 NaN 恒返回负值，会让边界静默失效：
         * {@code value < NaN} 为假、{@code value > NaN} 为假。Infinity 不受影响，属合法边界。
         */
        private void checkBoundary(T value) {
            if (value instanceof Double d && d.isNaN() || value instanceof Float f && f.isNaN()) {
                throw new IllegalArgumentException("Config entry '%s': boundary is NaN".formatted(name));
            }
        }
    }

    public static final class IntBuilder extends NumericBuilder<Integer, IntBuilder> {
        IntBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public IntBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return int.class;
        }
    }

    public static final class LongBuilder extends NumericBuilder<Long, LongBuilder> {
        LongBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public LongBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return long.class;
        }
    }

    public static final class ShortBuilder extends NumericBuilder<Short, ShortBuilder> {
        ShortBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public ShortBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return short.class;
        }
    }

    public static final class ByteBuilder extends NumericBuilder<Byte, ByteBuilder> {
        ByteBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public ByteBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return byte.class;
        }
    }

    public static final class FloatBuilder extends NumericBuilder<Float, FloatBuilder> {
        FloatBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public FloatBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return float.class;
        }
    }

    public static final class DoubleBuilder extends NumericBuilder<Double, DoubleBuilder> {
        DoubleBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public DoubleBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return double.class;
        }
    }

    public static final class BoolBuilder extends Builder<Boolean, BoolBuilder> {
        BoolBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public BoolBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return boolean.class;
        }

        /** 关键字名形式的默认值，理由同 {@link NumericBuilder#define(String)}。 */
        public BoolBuilder define(String token) {
            ConfigEntry.Keyword<Boolean> kw = keywords.get(token);
            if (kw == null) {
                throw new IllegalArgumentException(
                        "Config entry '%s': define(\"%s\") is not a declared keyword".formatted(name, token));
            }
            this.defaultValue = kw.supplier().get();
            this.defaultKeyword = token;
            return this;
        }
    }

    public static final class StringsBuilder extends Builder<String, StringsBuilder> {
        StringsBuilder(Config owner, String name) {
            super(owner, name);
        }

        @Override
        public StringsBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return String.class;
        }

        /** String 项：字符串既可以是已声明的关键字，也可以是字面量本身。 */
        public StringsBuilder define(String value) {
            ConfigEntry.Keyword<String> kw = keywords.get(value);
            if (kw != null) {
                this.defaultValue = kw.supplier().get();
                this.defaultKeyword = value;
                return this;
            }
            this.defaultValue = value;
            this.defaultKeyword = null;
            return this;
        }
    }

    public static final class EnumBuilder<T extends Enum<T>> extends Builder<T, EnumBuilder<T>> {

        private final Class<T> enumType;

        EnumBuilder(Config owner, String name, Class<T> type) {
            super(owner, name);
            this.enumType = type;
        }

        @Override
        public EnumBuilder<T> self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return enumType;
        }
    }

    // ---- 读盘 ----

    /**
     * 读本实例的文件。幂等：首次调用读磁盘并填充 ConfigEntry，之后直接返回。
     *
     * <p>
     * 调用点是持有本实例的类的静态块尾部，该类的初始化由首个引用它的类触发。方法之后不再有任何
     * 重新读盘的入口，热重载是结构性不可行的，见类注释。
     */
    public void init() {
        if (this.initialized) {
            return;
        }
        this.initialized = true;
        ConfigFile.load(this.file, this.fileNotes, this.entries);
    }

}
