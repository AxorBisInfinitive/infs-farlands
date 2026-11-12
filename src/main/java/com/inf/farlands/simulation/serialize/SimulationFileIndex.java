package com.inf.farlands.simulation.serialize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.level.ServerLevel;

import com.inf.farlands.InfsFarlands;

/**
 * ssa 文件名的映射表：三个区域坐标到 16 位小写十六进制编号。
 *
 * <p>
 * 存在的理由是路径长度。坐标直接编进文件名时，三段十进制数字的位数随坐标位宽增长；段号拓宽到 1024 位之后
 * 一段就是三百多位，三段加分隔符接近一千字符，任何文件系统都装不下。编号恒定 16 个字符，
 * {@code s.<16 位>.ssa} 恒为 26 个字符，路径总长因此与坐标位宽无关。
 *
 * <p>
 * 表在 {@code <维度目录>/ssa/mapping.json}，一个维度一份，形状是
 * {@code {"mapping": {"<x>.<y>.<z>": "<16 位十六进制>"}}}。key 是区域坐标，不是段号：一个 ssa 文件覆盖
 * 32 乘 32 乘 32 个段，键的粒度必须与文件粒度一致。段号到区域号的换算收在
 * {@code SimulationIO.regionOf} 一处。
 *
 * <p>
 * 四条不变式：
 *
 * <ol>
 * <li>编号只增，永不复用。复用会让两个区域指向同一个文件，那是不可恢复的数据污染。</li>
 * <li>{@code next} 从表里最大的编号加一推导，不另存字段，因此文件形状与约定完全一致。</li>
 * <li>落盘先于建文件：只有原子替换成功、{@link #ensure} 交回编号之后，调用方才建文件；替换失败时 {@link #ensure}
 * 返回 null，一个文件也不建。崩溃的后果只允许是「文件在、表里没有」，那是一个没人引用的孤儿文件；反向
 * 「表里有、文件没有」无害，读回按无数据处理。</li>
 * <li>读失败即抛，不重建。丢表等于把段数据变成不可达，静默重置是必须避免的形态。</li>
 * </ol>
 *
 * <p>
 * 线程：只在服务端主线程读写。它是文件命名的唯一权威，IO 线程只接收算好的路径。
 */
public final class SimulationFileIndex {

    private static final String FILE_NAME = "mapping.json";

    private static final String ROOT_KEY = "mapping";

    private static final Pattern ID_PATTERN = Pattern.compile("[0-9a-f]{16}");

    private static final String ID_FORMAT = "%016x";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 一个 ssa 文件覆盖的区域：三段都是段号右移五位的结果。 */
    public record Region(long x, long y, long z) {

        /** 表里的键形状。 */
        String key() {
            return this.x + "." + this.y + "." + this.z;
        }
    }

    /** 每维度一份，按维度目录缓存。 */
    private static final Map<Path, SimulationFileIndex> INSTANCES = new HashMap<>();

    private final Path dir;

    private final Path file;

    private final Path tmp;

    /** 区域到编号，保插入顺序，落盘时按它写。 */
    private final Map<Region, String> byRegion = new LinkedHashMap<>();

    /** 编号到区域，读表时用来查重。 */
    private final Map<String, Region> byId = new HashMap<>();

    private long next;

    private SimulationFileIndex(Path dir) {
        this.dir = dir;
        this.file = dir.resolve(FILE_NAME);
        this.tmp = dir.resolve(FILE_NAME + ".tmp");
    }

    /** 该 level 所属维度的表，首次调用时读盘。主线程调用。 */
    public static SimulationFileIndex of(ServerLevel level) {
        Path dir = SimulationIO.dimensionDir(level);
        SimulationFileIndex index = INSTANCES.get(dir);
        if (index == null) {
            index = load(dir);
            INSTANCES.put(dir, index);
        }
        return index;
    }

    /** 查该区域的编号，没有返回 null。不分配、不写盘，供读路用。 */
    public String find(Region region) {
        return this.byRegion.get(region);
    }

    /**
     * 取该区域的编号，没有就分配一个新的并落盘。只在写路调用。
     *
     * <p>
     * 落盘失败时把内存里刚加的两条与计数器一起回滚，返回 null。不回滚的话，这个编号下次会分给另一个区域，
     * 两个区域就会指向同一个文件。
     *
     * <p>
     * null 只表示这一轮没写出去，不是异常：调用方既不建文件也不读盘，等下一次周期刷盘重新分配到同一个编号。
     * 回滚发生在建文件之前，所以失败不会留下指向未登记编号的文件。
     */
    public String ensure(Region region) {
        String have = this.byRegion.get(region);
        if (have != null) {
            return have;
        }
        String id = String.format(ID_FORMAT, this.next);
        this.byRegion.put(region, id);
        this.byId.put(id, region);
        this.next++;
        try {
            save();
        } catch (RuntimeException e) {
            this.next--;
            this.byRegion.remove(region);
            this.byId.remove(id);
            InfsFarlands.LOGGER.error("farlands: ssa mapping save failed region={},{},{}",
                    region.x(), region.y(), region.z(), e);
            return null;
        }
        return id;
    }

    /** 停服清掉缓存，下一个世界重新读表。 */
    public static void clearWorldState() {
        INSTANCES.clear();
    }

    /**
     * 读表。文件不存在按空表处理，且什么都不写。
     *
     * <p>
     * 空表不落盘是刻意的：读路也会调到这里，若在装载时写一次，任何只是被查询过的维度都会凭空多出一个文件。
     * 第一次真正分配编号时才写出，见 {@link #ensure}。
     */
    private static SimulationFileIndex load(Path dir) {
        SimulationFileIndex index = new SimulationFileIndex(dir);
        if (!Files.exists(index.file)) {
            return index;
        }
        String text;
        try {
            text = Files.readString(index.file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("farlands: failed to read ssa mapping " + index.file, e);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new RuntimeException("farlands: malformed JSON in ssa mapping " + index.file, e);
        }
        if (!parsed.isJsonObject()) {
            throw new RuntimeException("farlands: ssa mapping root must be a JSON object: " + index.file);
        }
        JsonElement mapping = parsed.getAsJsonObject().get(ROOT_KEY);
        if (mapping == null || !mapping.isJsonObject()) {
            throw new RuntimeException("farlands: ssa mapping needs an object under \"" + ROOT_KEY + "\": "
                    + index.file);
        }
        for (Map.Entry<String, JsonElement> entry : mapping.getAsJsonObject().entrySet()) {
            Region region = parseRegion(entry.getKey(), index.file);
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new RuntimeException("farlands: ssa mapping value must be a string at " + entry.getKey()
                        + " in " + index.file);
            }
            String id = value.getAsString();
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new RuntimeException("farlands: ssa mapping id must be 16 lowercase hex chars, got " + id
                        + " at " + entry.getKey() + " in " + index.file);
            }
            if (index.byId.putIfAbsent(id, region) != null) {
                throw new RuntimeException("farlands: duplicate ssa mapping id " + id + " in " + index.file);
            }
            index.byRegion.put(region, id);
            long value2 = Long.parseUnsignedLong(id, 16);
            if (value2 < 0L) {
                // 编号高位置位即视作空间耗尽：再加一就回绕，会撞上已发出的号。
                throw new RuntimeException("farlands: ssa mapping id space exhausted at " + id + " in " + index.file);
            }
            if (value2 + 1L > index.next) {
                index.next = value2 + 1L;
            }
        }
        return index;
    }

    /** {@code x.y.z} 三段十进制，逐段严格解析。 */
    private static Region parseRegion(String key, Path file) {
        String[] parts = key.split("\\.", -1);
        if (parts.length != 3) {
            throw new RuntimeException("farlands: ssa mapping key must be x.y.z, got " + key + " in " + file);
        }
        try {
            return new Region(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            throw new RuntimeException("farlands: ssa mapping key is not three integers, got " + key + " in " + file,
                    e);
        }
    }

    /**
     * 全量重写，先写临时文件并刷盘，再原子替换。
     *
     * <p>
     * 原子替换不是所有文件系统都支持，网络盘上会抛 {@code AtomicMoveNotSupportedException}，那时退回非原子
     * 替换。非原子那一支的窗口是「旧文件已删、新文件未就位」，只在替换瞬间崩溃时出现。
     */
    private void save() {
        JsonObject mapping = new JsonObject();
        for (Map.Entry<Region, String> entry : this.byRegion.entrySet()) {
            mapping.addProperty(entry.getKey().key(), entry.getValue());
        }
        JsonObject root = new JsonObject();
        root.add(ROOT_KEY, mapping);
        byte[] text = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(this.dir);
            try (FileChannel channel = FileChannel.open(this.tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(text);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(this.tmp, this.file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(this.tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("farlands: failed to write ssa mapping " + this.file, e);
        }
    }
}
