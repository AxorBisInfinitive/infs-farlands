package com.inf.farlands.terrain.registry;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Dynamic;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import love.crysh.long2.binaries.binary1280.Binary1280;
import love.crysh.long2.binaries.binary256.Binary256;
import love.crysh.long2.binaries.binary640.Binary640;
import love.crysh.long2.ints.Int1024;
import love.crysh.long2.ints.Int256;
import love.crysh.long2.ints.Int512;

/**
 * 系统构造参数的键值视图。
 *
 * <p>键是裸字符串，按类型存取，类型集与配置系统一致。取值严格同类型，不做数值提升：存 double 的
 * 键只能用 getDouble 取。
 *
 * <p>每次实例化各用一份，由门面在实例化完成后冻结。所有失败都硬抛，不做默认值兜底：缺失键抛
 * IllegalArgumentException，类型不符抛 IllegalStateException，写入 null 抛
 * NullPointerException，冻结后写入抛 IllegalStateException。
 *
 * <p>唯一写入口是按类型的 setter。asMap 返回只读视图，绕过不了 setter，也绕过不了冻结。
 */
public final class SystemArgs {

    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean frozen;

    /**
     * 由一份落盘或界面产出的参数值造出视图。
     *
     * <p>
     * 类型分派与量程判定都在这里，两条入口共用同一份：`SystemsIO` 在构造系统之前用它，界面的规则求值
     * 也用它。越界即抛，与各类型 setter 的失败语义一致。
     */
    public static SystemArgs of(Map<String, Arg> args) {
        SystemArgs out = new SystemArgs();
        for (Map.Entry<String, Arg> entry : args.entrySet()) {
            set(out, entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static void set(SystemArgs args, String key, Arg arg) {
        switch (arg.type()) {
            case INT -> args.setInt(key, (int) range(key, arg, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case LONG -> args.setLong(key, exactLong(key, arg));
            case SHORT -> args.setShort(key, (short) range(key, arg, Short.MIN_VALUE, Short.MAX_VALUE));
            case BYTE -> args.setByte(key, (byte) range(key, arg, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case FLOAT -> args.setFloat(key, arg.asFloat(key));
            case DOUBLE -> args.setDouble(key, arg.asDouble(key));
            case INT256 -> args.setInt256(key, arg.asInt256(key));
            case INT512 -> args.setInt512(key, arg.asInt512(key));
            case INT1024 -> args.setInt1024(key, arg.asInt1024(key));
            case BINARY256 -> args.setBinary256(key, arg.asBinary256(key));
            case BINARY640 -> args.setBinary640(key, arg.asBinary640(key));
            case BINARY1280 -> args.setBinary1280(key, arg.asBinary1280(key));
            case BOOLEAN -> args.setBoolean(key, number(key, arg).longValue() != 0L);
            case STRING -> args.setString(key, string(key, arg));
            case ENUM -> setEnum(args, key, arg);
            // 兜底：漏一个分支的后果是这个参数静默不进视图，症状推迟到系统构造时抛缺失键。这里让它当场炸。
            default -> throw new IllegalStateException("farlands: unhandled system arg type " + arg.type());
        }
    }

    private static long range(String key, Arg arg, long min, long max) {
        long value = number(key, arg).longValue();
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "farlands: system arg " + key + " out of " + arg.type().id() + " range: " + value);
        }
        return value;
    }

    private static long exactLong(String key, Arg arg) {
        Number number = number(key, arg);
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("farlands: system arg " + key + " is not an integer in long range: " + number,
                    e);
        }
    }

    private static Number number(String key, Arg arg) {
        return number(key, arg.value());
    }

    private static <T> Number number(String key, Dynamic<T> value) {
        return value.getOps().getNumberValue(value.getValue()).getOrThrow(
                message -> new IllegalStateException("farlands: system arg " + key + " is not a number: " + message));
    }

    private static String string(String key, Arg arg) {
        return string(key, arg.value());
    }

    private static <T> String string(String key, Dynamic<T> value) {
        return value.getOps().getStringValue(value.getValue()).getOrThrow(
                message -> new IllegalStateException("farlands: system arg " + key + " is not a string: " + message));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void setEnum(SystemArgs args, String key, Arg arg) {
        String className = arg.enumClass().orElseThrow(() -> new IllegalStateException(
                "farlands: system arg " + key + " declared as enum but missing enumClass"));
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("farlands: system arg " + key + " enum class not found: " + className, e);
        }
        if (!type.isEnum()) {
            throw new IllegalStateException("farlands: system arg " + key + " class is not an enum: " + className);
        }
        String name = string(key, arg);
        try {
            args.setEnum(key, (Enum) Enum.valueOf((Class) type, name));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "farlands: system arg " + key + " has no such enum constant: " + className + "." + name, e);
        }
    }

    /** 键到值的只读视图。是视图不是快照，冻结前填充的变化对它可见。 */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(this.values);
    }

    public boolean has(String key) {
        return this.values.containsKey(key);
    }

    /** 冻结后 set 即抛。由门面在实例化完成后调用。 */
    public void freeze() {
        this.frozen = true;
    }

    public void setInt(String key, int value) {
        put(key, value);
    }

    public void setLong(String key, long value) {
        put(key, value);
    }

    public void setShort(String key, short value) {
        put(key, value);
    }

    public void setByte(String key, byte value) {
        put(key, value);
    }

    public void setFloat(String key, float value) {
        put(key, value);
    }

    public void setDouble(String key, double value) {
        put(key, value);
    }

    public void setBoolean(String key, boolean value) {
        put(key, value);
    }

    public void setString(String key, String value) {
        put(key, value);
    }

    public <E extends Enum<E>> void setEnum(String key, E value) {
        put(key, value);
    }

    public void setInt256(String key, Int256 value) {
        put(key, value);
    }

    public void setInt512(String key, Int512 value) {
        put(key, value);
    }

    public void setInt1024(String key, Int1024 value) {
        put(key, value);
    }

    public void setBinary256(String key, Binary256 value) {
        put(key, value);
    }

    public void setBinary640(String key, Binary640 value) {
        put(key, value);
    }

    public void setBinary1280(String key, Binary1280 value) {
        put(key, value);
    }

    public int getInt(String key) {
        return (Integer) get(key, Integer.class);
    }

    public long getLong(String key) {
        return (Long) get(key, Long.class);
    }

    public short getShort(String key) {
        return (Short) get(key, Short.class);
    }

    public byte getByte(String key) {
        return (Byte) get(key, Byte.class);
    }

    public float getFloat(String key) {
        return (Float) get(key, Float.class);
    }

    public double getDouble(String key) {
        return (Double) get(key, Double.class);
    }

    public boolean getBoolean(String key) {
        return (Boolean) get(key, Boolean.class);
    }

    public String getString(String key) {
        return (String) get(key, String.class);
    }

    public <E extends Enum<E>> E getEnum(String key, Class<E> type) {
        return type.cast(get(key, type));
    }

    public Int256 getInt256(String key) {
        return (Int256) get(key, Int256.class);
    }

    public Int512 getInt512(String key) {
        return (Int512) get(key, Int512.class);
    }

    public Int1024 getInt1024(String key) {
        return (Int1024) get(key, Int1024.class);
    }

    public Binary256 getBinary256(String key) {
        return (Binary256) get(key, Binary256.class);
    }

    public Binary640 getBinary640(String key) {
        return (Binary640) get(key, Binary640.class);
    }

    public Binary1280 getBinary1280(String key) {
        return (Binary1280) get(key, Binary1280.class);
    }

    private void put(String key, Object value) {
        if (this.frozen) {
            throw new IllegalStateException("SystemArgs is frozen: " + key);
        }
        if (value == null) {
            throw new NullPointerException("System arg is null: " + key);
        }
        Object previous = this.values.get(key);
        if (previous != null && previous.getClass() != value.getClass()) {
            throw new IllegalStateException("System arg '%s' already holds %s".formatted(key,
                    previous.getClass().getSimpleName()));
        }
        this.values.put(key, value);
    }

    private Object get(String key, Class<?> type) {
        Object value = this.values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing system arg: " + key);
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("System arg '%s' is %s, not %s".formatted(key,
                    value.getClass().getSimpleName(), type.getSimpleName()));
        }
        return value;
    }
}
