package com.inf.farlands.util.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 配置文件读写。
 *
 * <p>顶层结构：
 * <pre>{@code
 * {
 * "note": { "<source>": "<text>", ... }, // 注释来源 -> 注释
 * "lastWriteBackTime": <unixSeconds>, // 最后回写时间戳
 * "settings": {
 * "<entryName>": { "note": { ... }, "value": <literal|keyword>, "default":
 * <literal|keyword>,
 * "allow": { ... } },
 * ...
 * }
 * }
 * }</pre>
 *
 * <p>{@code allow} 承载该条目值域与替代取值的全部声明，子键各自可选：
 * <pre>{@code
 * "allow": {
 * "min": <lo>, // 只声明下界时仅此键
 * "max": <hi>, // 只声明上界时仅此键
 * "enums": [ ... ], // 只出现在枚举条目上，列出该枚举当前的全部取值
 * "keyword": [ { "value": <关键字>, "note": { ... } } ] // 该条目声明的全部关键字
 * }
 * }</pre>
 *
 * <p>{@code min} / {@code max} / {@code enums} / {@code keyword}
 * 都是<b>信息字段</b>，供手改配置时
 * 对照，读盘期不参与校验：真正的判定是边界生成的约束与 {@code Enum.valueOf}。一个子键都没有时
 * {@code allow} 整体不写。
 *
 * <p>{@code "value"} 与 {@code "default"} 除字面量外还接受该条目已声明的关键字名；命中时由该
 * 关键字的取值器在读盘期求值一次。没声明过的字符串不会被当作关键字。
 *
 * <p>回写一致性：`load` 会把下列漂移都标脏并触发一次全量重写——{@code default} 与代码声明不符、
 * {@code allow} 与代码声明不符、以及条目层与关键字层 {@code note} 里由 {@code comment(...)} 声明的
 * 来源键与声明不符。{@code note} 的额外键（代码没声明的来源）不参与比较，也不会被删：
 * 重写时是覆盖式写入声明键。
 */
public final class ConfigFile {

    /** 缩进用四个空格。 */
    private static final String INDENT = "    ";
    private static final String NOTE = "note";
    private static final String LAST_WRITE_BACK_TIME = "lastWriteBackTime";
    private static final String SETTINGS = "settings";
    private static final String VALUE = "value";
    private static final String DEFAULT = "default";
    private static final String ALLOW = "allow";
    private static final String MIN = "min";
    private static final String MAX = "max";
    private static final String ENUMS = "enums";
    private static final String KEYWORD = "keyword";

    private ConfigFile() {
    }

    /**
     * 加载配置文件，若不存在则生成默认；若存在则读值覆盖，漂移或缺键则回写。
     * 空文件 / 纯空白 / 字面量 null 视为无配置 -> 重新生成默认。
     *
     * <p>
     * 结构不符则抛异常，不静默重置：JSON 语法错、根不是对象、settings 或条目不是对象、
     * 条目缺 value、allow 及 allow.keyword / allow.enums 形状不符，都是这份文件不是本配置的情形。
     * 键缺失（settings、lastWriteBackTime、note、default、allow）与代码侧声明漂移则照旧补写。
     *
     * <p>
     * 缺键不等于没有值可读。只有 settings 缺失才意味着这份文件没有值；时间戳缺失时 settings 可能
     * 完整，所以读值一律执行，补键由随后的写盘完成。
     */
    public static void load(Path file, Map<String, String> fileNotes,
            Map<String, ConfigEntry<?>> entries) {
        if (!Files.exists(file)) {
            applyDefaults(entries);
            write(file, null, fileNotes, entries);
            return;
        }
        JsonElement parsed;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(r);
        } catch (com.google.gson.JsonParseException e) {
            // 非法 JSON 语法即结构错，不重置：静默重置会把用户手改的全部内容丢掉
            throw new RuntimeException("Malformed JSON in config %s: %s".formatted(file, e.getMessage()), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config %s: %s".formatted(file, e.getMessage()), e);
        }
        if (parsed.isJsonNull()) {
            applyDefaults(entries);
            write(file, null, fileNotes, entries);
            return;
        }
        if (!parsed.isJsonObject()) {
            throw new RuntimeException(
                    "Config %s: root must be a JSON object, got %s".formatted(file, parsed));
        }
        JsonObject root = parsed.getAsJsonObject();

        boolean[] missing = { false };
        JsonObject settings = ensureSettings(root, file, missing);

        // 读每个注册项：存在->校验类型与值域并覆盖，同时对齐 default/allow/note；缺失->补默认
        boolean[] dirty2 = { false };
        for (Map.Entry<String, ConfigEntry<?>> e : entries.entrySet()) {
            String name = e.getKey();
            ConfigEntry<?> entry = e.getValue();
            if (settings.has(name)) {
                if (!settings.get(name).isJsonObject()) {
                    throw new RuntimeException(
                            "Config %s entry '%s': must be a JSON object, got %s"
                                    .formatted(file, name, settings.get(name)));
                }
                JsonObject obj = settings.getAsJsonObject(name);
                validateAllowShape(obj, name, file);
                if (!obj.has(VALUE)) {
                    throw new RuntimeException("Config entry '%s' missing 'value' in %s".formatted(name, file));
                }
                Object v = readValue(obj.get(VALUE), entry, file);
                setValue(entry, v);
                if (align(obj, DEFAULT, defaultValueJson(entry))) {
                    dirty2[0] = true;
                }
                // allow 整体替换：代码侧已不存在的子键随之消失，不需要逐个清残留
                if (hasAllow(entry)) {
                    if (align(obj, ALLOW, allowJson(entry, obj))) {
                        dirty2[0] = true;
                    }
                } else if (obj.has(ALLOW)) {
                    obj.remove(ALLOW); // 代码侧已无任何值域声明，残留键要清掉
                    dirty2[0] = true;
                }
                if (noteDrift(obj, entry.notes())) {
                    mergeNotesInto(obj, entry.notes());
                    dirty2[0] = true;
                }
                if (keywordNoteDrift(obj, entry)) {
                    dirty2[0] = true;
                }
            } else {
                JsonObject obj = new JsonObject();
                obj.add(NOTE, toNotesJson(entry.notes()));
                // 新条目的值就是它的默认值；默认是关键字时取值器已在声明处求过值
                applyDefault(entry);
                obj.add(VALUE, valueJson(entry));
                obj.add(DEFAULT, defaultValueJson(entry));
                JsonObject allow = allowJson(entry, null);
                if (allow != null) {
                    obj.add(ALLOW, allow);
                }
                settings.add(name, obj);
                dirty2[0] = true;
            }
        }
        if (dirty2[0] || missing[0]) {
            write(file, root, fileNotes, entries);
        } else {
            // 内容没有漂移，但磁盘上的字节可能仍不是本 writer 会写出的形状，例如缩进被改过。
            // 这份文件只有一个写入者，所以「格式规范」等价于「磁盘字节 == 现在会写出的字节」。
            boolean same;
            try {
                same = Files.readString(file, StandardCharsets.UTF_8).equals(format(root));
            } catch (IOException e) {
                same = true; // 读不回来就不动它，交给下一次读盘
            }
            if (!same) {
                write(file, root, fileNotes, entries);
            }
        }
    }

    /**
     * settings 与 lastWriteBackTime 的存在性检查。缺失的键只记标志，不在这里写值。
     *
     * <p>
     * 时间戳缺失不代表没有值可读，同一次加载里 settings 可能完整。在这里造一个值，会把「补了一个键」
     * 与「没有值可保留」写成同一个信号，调用方只能整份重建。补键统一由写盘完成。
     */
    private static JsonObject ensureSettings(JsonObject root, Path file, boolean[] missing) {
        if (!root.has(LAST_WRITE_BACK_TIME)) {
            missing[0] = true;
        }
        if (!root.has(SETTINGS)) {
            root.add(SETTINGS, new JsonObject());
            missing[0] = true;
        } else if (!root.get(SETTINGS).isJsonObject()) {
            throw new RuntimeException(
                    "Config %s: \"%s\" must be a JSON object, got %s"
                            .formatted(file, SETTINGS, root.get(SETTINGS)));
        }
        return root.getAsJsonObject(SETTINGS);
    }

    // 写文件

    /**
     * 全量重写。
     *
     * <p>
     * {@code existingRoot} 是已解析的旧文件（文件不存在或损坏时传 null），只用于让 {@code note}
     * 里的额外来源键活下来：写入是覆盖式的，声明键被更新，其余键原样保留。
     *
     * <p>
     * 时间戳取本方法执行时刻：它记的是这一次回写，与文件何时创建无关，因此不由调用方传入。
     * 本方法不改动传入的树，加载期那个字节比较读到的仍是磁盘上那份时间戳。
     */
    private static void write(Path file, JsonObject existingRoot, Map<String, String> fileNotes,
            Map<String, ConfigEntry<?>> entries) {
        JsonObject root = new JsonObject();
        root.add(NOTE, mergeNotes(existingRoot == null ? null : existingRoot.get(NOTE), fileNotes));
        root.addProperty(LAST_WRITE_BACK_TIME, System.currentTimeMillis() / 1000L);
        JsonObject oldSettings = existingRoot != null && existingRoot.get(SETTINGS) != null
                && existingRoot.get(SETTINGS).isJsonObject() ? existingRoot.getAsJsonObject(SETTINGS) : null;
        JsonObject settings = new JsonObject();
        for (Map.Entry<String, ConfigEntry<?>> e : entries.entrySet()) {
            ConfigEntry<?> entry = e.getValue();
            JsonObject oldObj = oldSettings != null && oldSettings.get(e.getKey()) != null
                    && oldSettings.get(e.getKey()).isJsonObject()
                            ? oldSettings.getAsJsonObject(e.getKey())
                            : null;
            JsonObject obj = new JsonObject();
            obj.add(NOTE, mergeNotes(oldObj == null ? null : oldObj.get(NOTE), entry.notes()));
            // 这里写当前值而不是默认值：触发的回填重写不能把用户改过的值冲掉；
            // 当前值命中关键字时必须写回关键字名，否则第一次重写就把关键字冻结成数字
            obj.add(VALUE, valueJson(entry));
            obj.add(DEFAULT, defaultValueJson(entry));
            JsonObject allow = allowJson(entry, oldObj);
            if (allow != null) {
                obj.add(ALLOW, allow);
            }
            settings.add(e.getKey(), obj);
        }
        root.add(SETTINGS, settings);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                JsonWriter jw = new JsonWriter(w);
                jw.setIndent(INDENT);
                jw.setHtmlSafe(false);
                Streams.write(root, jw);
                jw.close();
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write config %s: %s".formatted(file, ex.getMessage()), ex);
        }
    }

    /**
     * 把一棵树渲染成落盘字节。{@link #write} 与漂移比较共用同一个渲染路径，
     * 两者分头实现会让「格式已规范」这个判据失真。
     *
     * <p>
     * 不用 {@code GsonBuilder.setPrettyPrinting()}：Gson 2.10.1 的缩进固定两个空格，
     * 没有可定制的策略类。{@code toJson} 内部也只是 {@code Streams.write} 加一个 JsonWriter，
     * 所以这里等价，只是缩进由 {@link #INDENT} 给。{@code setHtmlSafe(false)} 对应
     * {@code disableHtmlEscaping}；不设 {@code setSerializeNulls} 即保持其默认的丢弃 null。
     */
    private static String format(JsonElement root) {
        StringWriter sw = new StringWriter();
        try {
            JsonWriter jw = new JsonWriter(sw);
            jw.setIndent(INDENT);
            jw.setHtmlSafe(false);
            Streams.write(root, jw);
            jw.close();
        } catch (IOException e) {
            // StringWriter 不抛 IOException，此时只可能是树里出现了非法结构
            throw new RuntimeException("Failed to render config JSON", e);
        }
        return sw.toString();
    }

    // 值序列化

    /** 当前值的 JSON：命中关键字则写关键字名，否则写字面值。 */
    private static JsonElement valueJson(ConfigEntry<?> entry) {
        if (entry.isKeyword()) {
            return new JsonPrimitive(entry.keyword());
        }
        return toValueJson(entry.get(), entry.type());
    }

    /** 默认值的 JSON：默认是关键字则写关键字名，否则写字面值。 */
    private static JsonElement defaultValueJson(ConfigEntry<?> entry) {
        if (entry.isDefaultKeyword()) {
            return new JsonPrimitive(entry.defaultKeyword());
        }
        return toValueJson(entry.defaultValue(), entry.type());
    }

    // allow 序列化

    /** 该条目是否有任何 allow 子键。 */
    private static boolean hasAllow(ConfigEntry<?> entry) {
        return entry.min() != null || entry.max() != null
                || entry.type().isEnum() || !entry.keywords().isEmpty();
    }

    /**
     * 组装该条目的 {@code allow}。没有子键时返回 null，调用方据此不写该键——
     * 写空对象会让 {@link #align} 每次都判漂移，文件永久重写。
     *
     * <p>
     * {@code oldObj} 是文件里的旧条目，只用于让关键字 note 的额外来源键活下来。
     */
    private static JsonObject allowJson(ConfigEntry<?> entry, JsonObject oldObj) {
        if (!hasAllow(entry)) {
            return null;
        }
        JsonObject allow = new JsonObject();
        // min / max 各自独立：只声明一侧就只写一侧，另一侧不出现
        if (entry.min() != null) {
            allow.add(MIN, new JsonPrimitive((Number) entry.min()));
        }
        if (entry.max() != null) {
            allow.add(MAX, new JsonPrimitive((Number) entry.max()));
        }
        if (entry.type().isEnum()) {
            allow.add(ENUMS, enumArray(entry.type()));
        }
        if (!entry.keywords().isEmpty()) {
            allow.add(KEYWORD, keywordArray(entry, oldObj));
        }
        return allow;
    }

    private static JsonArray enumArray(Class<?> type) {
        JsonArray arr = new JsonArray();
        for (Object constant : type.getEnumConstants()) {
            arr.add(((Enum<?>) constant).name());
        }
        return arr;
    }

    @SuppressWarnings("rawtypes")
    private static JsonArray keywordArray(ConfigEntry<?> entry, JsonObject oldObj) {
        JsonArray arr = new JsonArray();
        // 通配符捕获无法写进 Map.Entry 的形参，只能按 raw 迭代
        for (Map.Entry e : entry.keywords().entrySet()) {
            String kwd = (String) e.getKey();
            ConfigEntry.Keyword<?> spec = (ConfigEntry.Keyword<?>) e.getValue();
            JsonObject el = new JsonObject();
            el.addProperty(VALUE, kwd);
            el.add(NOTE, mergeNotes(keywordNoteIn(oldObj, kwd), spec.notes()));
            arr.add(el);
        }
        return arr;
    }

    /** 取旧文件里某个关键字元素的 note，没有则 null。 */
    private static JsonElement keywordNoteIn(JsonObject oldObj, String kwd) {
        if (oldObj == null || !oldObj.has(ALLOW) || !oldObj.get(ALLOW).isJsonObject()) {
            return null;
        }
        JsonElement kw = oldObj.getAsJsonObject(ALLOW).get(KEYWORD);
        if (kw == null || !kw.isJsonArray()) {
            return null;
        }
        for (JsonElement el : kw.getAsJsonArray()) {
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                JsonElement v = o.get(VALUE);
                if (v != null && v.isJsonPrimitive() && kwd.equals(v.getAsString())) {
                    return o.get(NOTE);
                }
            }
        }
        return null;
    }

    // notes 序列化

    /**
     * allow 及其子键的形状检查。这些位置只承载本 mod 生成的声明，形状不符说明文件不是本配置写的，
     * 不能猜着修。
     */
    private static void validateAllowShape(JsonObject entryObj, String name, Path file) {
        if (!entryObj.has(ALLOW)) {
            return;
        }
        JsonElement allow = entryObj.get(ALLOW);
        if (!allow.isJsonObject()) {
            throw new RuntimeException("Config %s entry '%s': \"%s\" must be a JSON object, got %s"
                    .formatted(file, name, ALLOW, allow));
        }
        JsonObject allowObj = allow.getAsJsonObject();
        JsonElement enums = allowObj.get(ENUMS);
        if (enums != null && !enums.isJsonArray()) {
            throw new RuntimeException("Config %s entry '%s': \"%s.%s\" must be a JSON array, got %s"
                    .formatted(file, name, ALLOW, ENUMS, enums));
        }
        JsonElement keyword = allowObj.get(KEYWORD);
        if (keyword == null) {
            return;
        }
        if (!keyword.isJsonArray()) {
            throw new RuntimeException("Config %s entry '%s': \"%s.%s\" must be a JSON array, got %s"
                    .formatted(file, name, ALLOW, KEYWORD, keyword));
        }
        for (JsonElement el : keyword.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                throw new RuntimeException(
                        "Config %s entry '%s': each \"%s.%s\" element must be a JSON object, got %s"
                                .formatted(file, name, ALLOW, KEYWORD, el));
            }
            JsonElement v = el.getAsJsonObject().get(VALUE);
            if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                throw new RuntimeException(
                        "Config %s entry '%s': each \"%s.%s\" element needs a string \"%s\", got %s"
                                .formatted(file, name, ALLOW, KEYWORD, VALUE, v));
            }
        }
    }

    private static JsonObject toNotesJson(Map<String, String> notes) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : notes.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    /** 覆盖式合并：写入声明键，保留 {@code existing} 里其余键。不改动传入的树。 */
    private static JsonObject mergeNotes(JsonElement existing, Map<String, String> declared) {
        JsonObject note = existing != null && existing.isJsonObject()
                ? existing.getAsJsonObject().deepCopy()
                : new JsonObject();
        declared.forEach(note::addProperty);
        return note;
    }

    private static void mergeNotesInto(JsonObject entryObj, Map<String, String> declared) {
        entryObj.add(NOTE, mergeNotes(entryObj.get(NOTE), declared));
    }

    /** 声明过的来源键是否与文件里的不符。额外键不参与比较。 */
    private static boolean noteDrift(JsonObject noteHolder, Map<String, String> declared) {
        JsonElement el = noteHolder.get(NOTE);
        JsonObject have = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        for (Map.Entry<String, String> e : declared.entrySet()) {
            JsonElement v = have == null ? null : have.get(e.getKey());
            if (v == null || !v.isJsonPrimitive() || !v.getAsString().equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** 关键字层的 note 漂移：声明过的来源键与文件里该关键字元素不符即算漂移。 */
    @SuppressWarnings("rawtypes")
    private static boolean keywordNoteDrift(JsonObject entryObj, ConfigEntry<?> entry) {
        if (entry.keywords().isEmpty() || !entryObj.has(ALLOW)) {
            return !entry.keywords().isEmpty();
        }
        JsonElement kw = entryObj.getAsJsonObject(ALLOW).get(KEYWORD);
        if (kw == null || !kw.isJsonArray()) {
            return true;
        }
        // 通配符捕获无法写进 Map.Entry 的形参，只能按 raw 迭代
        for (Map.Entry e : entry.keywords().entrySet()) {
            String kwd = (String) e.getKey();
            ConfigEntry.Keyword<?> spec = (ConfigEntry.Keyword<?>) e.getValue();
            JsonObject holder = new JsonObject();
            holder.add(NOTE, keywordNoteIn(entryObj, kwd));
            if (noteDrift(holder, spec.notes())) {
                return true;
            }
        }
        return false;
    }

    /** 把 obj 的 key 对齐到 want：缺失或不符都替换，返回是否发生了替换。 */
    private static boolean align(JsonObject obj, String key, JsonElement want) {
        JsonElement have = obj.get(key);
        if (have != null && have.equals(want)) {
            return false;
        }
        obj.add(key, want);
        return true;
    }

    // value 序列化：类型分派

    private static JsonElement toValueJson(Object value, Class<?> type) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (type == int.class || type == Integer.class) {
            return new JsonPrimitive((Integer) value);
        }
        if (type == long.class || type == Long.class) {
            return new JsonPrimitive((Long) value);
        }
        if (type == short.class || type == Short.class) {
            return new JsonPrimitive((Short) value);
        }
        if (type == byte.class || type == Byte.class) {
            return new JsonPrimitive((Byte) value);
        }
        if (type == float.class || type == Float.class) {
            return new JsonPrimitive((Float) value);
        }
        if (type == double.class || type == Double.class) {
            return new JsonPrimitive((Double) value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return new JsonPrimitive((Boolean) value);
        }
        if (type == String.class) {
            return new JsonPrimitive((String) value);
        }
        if (type.isEnum()) {
            return new JsonPrimitive(((Enum<?>) value).name());
        }
        throw new IllegalArgumentException("Unsupported config type: %s".formatted(type));
    }

    // value 反序列化：关键字名 -> 取值器；否则类型分派 + 严格校验 + 值域约束

    private static Object readValue(JsonElement el, ConfigEntry<?> entry, Path file) {
        String kwd = keywordToken(el, entry);
        if (kwd != null) {
            entry.setKeyword(kwd);
            // 关键字派生结果不过值域约束：它由代码算出，不是用户输入
            return entry.keywords().get(kwd).supplier().get();
        }
        entry.setKeyword(null);
        Object v = readTyped(el, entry, file);
        checkConstraint(entry, v, file);
        return v;
    }

    /**
     * 该位置的值是否命中本条目声明的某个关键字，是则返回其名。
     * 判据是「在该条目的 {@code allow.keyword} 里」，没声明过的字符串不会被截走。
     */
    private static String keywordToken(JsonElement el, ConfigEntry<?> entry) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = el.getAsString();
        return entry.keywords().containsKey(s) ? s : null;
    }

    private static Object readTyped(JsonElement el, ConfigEntry<?> entry, Path file) {
        Class<?> type = entry.type();
        if (type == int.class || type == Integer.class) {
            long v = requireNumber(el, file).longValue();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of int range"
                        .formatted(file, entry.name(), v));
            }
            return (int) v;
        }
        if (type == long.class || type == Long.class) {
            // Gson getAsLong 对超 long 范围会饱和，用 BigDecimal 精确校验
            return requireIntegralExact(el, file, Long.MIN_VALUE, Long.MAX_VALUE, entry.name());
        }
        if (type == short.class || type == Short.class) {
            long v = requireNumber(el, file).longValue();
            if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of short range"
                        .formatted(file, entry.name(), v));
            }
            return (short) v;
        }
        if (type == byte.class || type == Byte.class) {
            long v = requireNumber(el, file).longValue();
            if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of byte range"
                        .formatted(file, entry.name(), v));
            }
            return (byte) v;
        }
        if (type == float.class || type == Float.class) {
            double d = requireNumber(el, file).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)
                    || d < -Float.MAX_VALUE || d > Float.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of float range"
                        .formatted(file, entry.name(), d));
            }
            return (float) d;
        }
        if (type == double.class || type == Double.class) {
            double d = requireNumber(el, file).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new RuntimeException("Config %s entry '%s': invalid double value %s"
                        .formatted(file, entry.name(), d));
            }
            return d;
        }
        if (type == boolean.class || type == Boolean.class) {
            return requirePrimitive(el, file).getAsBoolean();
        }
        if (type == String.class) {
            return requirePrimitive(el, file).getAsString();
        }
        if (type.isEnum()) {
            String s = requirePrimitive(el, file).getAsString();
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object v = Enum.valueOf((Class<? extends Enum>) type, s);
            return v; // 未知枚举名 -> IllegalArgumentException 向上抛
        }
        throw new IllegalArgumentException("Unsupported config type: %s".formatted(type));
    }

    /** 值域约束。异构注册表只能走 raw 桥接，与 {@link #setValue} 同形。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void checkConstraint(ConfigEntry entry, Object v, Path file) {
        ConfigEntry.Constraint c = entry.constraint();
        if (c == null) {
            return;
        }
        try {
            c.check(v);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Config %s entry '%s': %s".formatted(file, entry.name(), e.getMessage()), e);
        }
    }

    /** 读 Number，非数值抛异常。 */
    private static Number requireNumber(JsonElement el, Path file) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new RuntimeException("Config %s: expected number, got %s".formatted(file, el));
        }
        return el.getAsJsonPrimitive().getAsNumber();
    }

    /** 整型精确读取：用 BigDecimal 校验不丢精度。 */
    private static long requireIntegralExact(JsonElement el, Path file,
            long min, long max, String name) {
        Number n = requireNumber(el, file);
        java.math.BigDecimal bd = new java.math.BigDecimal(n.toString());
        try {
            long v = bd.longValueExact(); // 非整数值或超范围抛 ArithmeticException
            if (v < min || v > max) {
                throw new ArithmeticException();
            }
            return v;
        } catch (ArithmeticException e) {
            throw new RuntimeException("Config %s entry '%s': value %s not a valid integer in [%s, %s]"
                    .formatted(file, name, n, min, max));
        }
    }

    private static JsonPrimitive requirePrimitive(JsonElement el, Path file) {
        if (el == null || !el.isJsonPrimitive()) {
            throw new RuntimeException("Config %s: expected primitive value, got %s".formatted(file, el));
        }
        return el.getAsJsonPrimitive();
    }

    // 泛型桥接：写回 entry.value

    /**
     * 生成与重置路径：把默认值落进每个条目。必须走这一步——关键字默认值的条目在构造时
     * {@code value} 是 null，直接落盘会写出没有 {@code value} 键的文件，而消费方读到的也是 null。
     */
    private static void applyDefaults(Map<String, ConfigEntry<?>> entries) {
        for (ConfigEntry<?> entry : entries.values()) {
            applyDefault(entry);
        }
    }

    /** 把默认值与其来源落进条目。关键字默认值的结果不过值域约束。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void applyDefault(ConfigEntry entry) {
        entry.set(entry.defaultValue());
        entry.setKeyword(entry.defaultKeyword());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void setValue(ConfigEntry entry, Object v) {
        entry.set(v);
    }
}
