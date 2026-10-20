package com.inf.farlands.terrain.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.inf.farlands.InfsFarlands;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 五族系统选择与传参，落盘为 {@code data/infs-farlands/systems.dat}。
 *
 * <p>
 * 维度为键。level.dat 与 SavedDataStorage 都是世界一份，三个维度共用同一个对象，所以这份数据
 * 只解析一次，由该世界第一个构造的 level 负责，取用入口见 {@link SystemsHolder}。
 *
 * <p>
 * 参数带显式的声明类型。NBT 的 boolean 与 byte 共用 ByteTag，原始类型 Codec 对超范围数值静默
 * 截断，两者都让"按 NBT 标签反推类型"不成立，所以类型写进数据，越界判定在取值时按声明类型做。
 */
public final class SystemsData extends SavedData {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "systems");

    public static final Codec<SystemsData> CODEC = Codec
            .unboundedMap(Identifier.CODEC, LevelSelection.CODEC)
            .xmap(SystemsData::new, SystemsData::levels);

    /**
     * dataFixType 在本 port 走不到：读走 {@link SystemsIO} 的自读，写只经 SavedDataStorage 的编码
     * 路径，而那条路径不碰 dataFixType。取一个注册为 remainder 的常量，将来即便被 vanilla 的读路径
     * 碰到，数据修复也不会改写内容。
     */
    public static final SavedDataType<SystemsData> TYPE = new SavedDataType<>(
            ID, SystemsData::new, CODEC, DataFixTypes.SAVED_DATA_WEATHER);

    /** 维度 id 到该维度的五族选择，保插入顺序以便落盘可读。 */
    private final Map<Identifier, LevelSelection> levels;

    /** SavedDataType 要求无参构造器；本 port 不走 computeIfAbsent，这个空对象不会被采用。 */
    public SystemsData() {
        this(Map.of());
    }

    public SystemsData(Map<Identifier, LevelSelection> levels) {
        this.levels = Collections.unmodifiableMap(new LinkedHashMap<>(levels));
    }

    /** 全部维度到其选择。codec 写侧要用，客户端按维度预填创建世界页签也要用。 */
    public Map<Identifier, LevelSelection> levels() {
        return this.levels;
    }

    /** 该维度的选择，缺失返回 null，由调用方决定失败语义。 */
    public LevelSelection selection(Identifier dimension) {
        return this.levels.get(dimension);
    }

    /** 一个维度的五族选择。 */
    public record LevelSelection(SystemSelection terrain, SystemSelection biome, SystemSelection surface,
            SystemSelection carver, SystemSelection decoration) {

        public static final Codec<LevelSelection> CODEC = RecordCodecBuilder.create(i -> i.group(
                SystemSelection.CODEC.fieldOf("terrain").forGetter(LevelSelection::terrain),
                SystemSelection.CODEC.fieldOf("biome").forGetter(LevelSelection::biome),
                SystemSelection.CODEC.fieldOf("surface").forGetter(LevelSelection::surface),
                SystemSelection.CODEC.fieldOf("carver").forGetter(LevelSelection::carver),
                SystemSelection.CODEC.fieldOf("decoration").forGetter(LevelSelection::decoration))
                .apply(i, LevelSelection::new));
    }

    /** 一族的选择：注册 id 加该族的构造参数。 */
    public record SystemSelection(Identifier id, Map<String, Arg> args) {

        /** 参数名到 Arg 的映射。落盘与界面自由参数框共用同一份写法。 */
        public static final Codec<Map<String, Arg>> ARGS_CODEC = Codec.unboundedMap(Codec.STRING, Arg.CODEC);

        public static final Codec<SystemSelection> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("id").forGetter(SystemSelection::id),
                ARGS_CODEC.fieldOf("args").forGetter(SystemSelection::args))
                .apply(i, SystemSelection::new));
    }

    /**
     * 一个构造参数：声明类型加原始 NBT 值。
     *
     * <p>
     * 值经 Codec.PASSTHROUGH 原样带过，文件里保留它本来的标签类型，手改配置时读得懂。浮点参数例外：
     * 它们的值一律是字符串，原文进出，避免 `DoubleTag.valueOf` 把负零归一成正零，解析推迟到
     * {@link Arg#asDouble} 与 {@link Arg#asFloat}。其余类型的一致性由 {@link ArgType} 在取值时判定，
     * 不靠标签反推。
     *
     * <p>
     * enum 另带类名。SystemArgs.getEnum 走 Class.cast，只有重建出真枚举实例才取得到。
     */
    public record Arg(ArgType type, Dynamic<?> value, Optional<String> enumClass) {

        public static final Codec<Arg> CODEC = RecordCodecBuilder.create(i -> i.group(
                ArgType.CODEC.fieldOf("type").forGetter(Arg::type),
                Codec.PASSTHROUGH.fieldOf("value").forGetter(Arg::value),
                Codec.STRING.optionalFieldOf("enumClass").forGetter(Arg::enumClass))
                .apply(i, Arg::new));

        public static Arg ofInt(int value) {
            return new Arg(ArgType.INT, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createInt(value)),
                    Optional.empty());
        }

        public static Arg ofShort(short value) {
            return new Arg(ArgType.SHORT, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createShort(value)),
                    Optional.empty());
        }

        public static Arg ofByte(byte value) {
            return new Arg(ArgType.BYTE, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createByte(value)),
                    Optional.empty());
        }

        public static Arg ofLong(long value) {
            return new Arg(ArgType.LONG, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createLong(value)),
                    Optional.empty());
        }

        /** 浮点参数按文本存，值是字符串，解析推迟到取值时。见 {@link #asDouble(String)}。 */
        public static Arg ofFloatText(String value) {
            return new Arg(ArgType.FLOAT, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createString(value)),
                    Optional.empty());
        }

        /** 浮点参数按文本存，解析推迟到取值时。见 {@link #asDouble(String)}。 */
        public static Arg ofDoubleText(String value) {
            return new Arg(ArgType.DOUBLE, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createString(value)),
                    Optional.empty());
        }

        /**
         * 浮点参数的取值。
         *
         * <p>
         * 值必须是字符串：原文进文件、原文出文件，因此负零、非有限、次正规与用户自己的写法都能原样往返。
         * 数字标签是旧格式，这里直接拒，不做两种形态的接收。
         */
        public double asDouble(String key) {
            String text = numberText(key);
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("farlands: system arg " + key + " is not a number: " + text, e);
            }
        }

        /** 同 {@link #asDouble}，额外判 float 的有限量程。非有限放行。 */
        public float asFloat(String key) {
            double value = asDouble(key);
            if (Double.isFinite(value) && Math.abs(value) > Float.MAX_VALUE) {
                throw new IllegalStateException(
                        "farlands: system arg " + key + " out of float range: " + value);
            }
            return (float) value;
        }

        private String numberText(String key) {
            return text(key, this.value);
        }

        private static <T> String text(String key, Dynamic<T> value) {
            return value.getOps().getStringValue(value.getValue()).getOrThrow(
                    error -> new IllegalStateException(
                            "farlands: system arg " + key + " 需要字符串形式的浮点数，这个文件是旧格式: " + error));
        }

        public static Arg ofBoolean(boolean value) {
            return new Arg(ArgType.BOOLEAN, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createBoolean(value)),
                    Optional.empty());
        }

        public static Arg ofString(String value) {
            return new Arg(ArgType.STRING, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createString(value)),
                    Optional.empty());
        }

        /** 枚举值存常量名，类名进 enumClass：取值时要重建真枚举实例，光有名字取不出来。 */
        public static <E extends Enum<E>> Arg ofEnum(E value) {
            return new Arg(ArgType.ENUM, new Dynamic<>(NbtOps.INSTANCE, NbtOps.INSTANCE.createString(value.name())),
                    Optional.of(value.getDeclaringClass().getName()));
        }
    }

    /** 参数声明类型。文件里写小写名，未知名直接抛。 */
    public enum ArgType {
        INT, LONG, SHORT, BYTE, FLOAT, DOUBLE, BOOLEAN, STRING, ENUM;

        public static final Codec<ArgType> CODEC = Codec.STRING.xmap(ArgType::of, ArgType::id);

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static ArgType of(String id) {
            for (ArgType type : values()) {
                if (type.id().equals(id)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown system arg type: " + id);
        }
    }
}
