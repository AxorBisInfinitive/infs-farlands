package com.inf.farlands.simulation.serialize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;

import net.minecraft.server.level.ServerLevel;

import com.inf.farlands.InfsFarlands;
import love.crysh.long2.ints.Int1024;

/**
 * ssa 文件名的映射表：三个区域坐标到 16 位小写十六进制编号。
 *
 * <p>
 * 存在的理由是路径长度。坐标直接编进文件名时，三段十进制数字的位数随坐标位宽增长；段号拓宽到 1024 位之后
 * 一段就是三百多位，三段加分隔符接近一千字符，任何文件系统都装不下。编号恒定 16 个字符，
 * {@code s.<16 位>.ssa} 恒为 26 个字符，路径总长因此与坐标位宽无关。
 *
 * <p>
 * 表在 {@code <维度目录>/ssa/mapping.dat}，一个维度一份。格式是二进制，头部 16 字节加 lz4 压缩的定长记录区：
 *
 * <pre>
 * 0   int   magic 0x5353414D 即 "SSAM"
 * 4   int   格式版本 1
 * 8   int   条目数
 * 12  int   压缩后记录区的字节数
 * 16  记录区，每条 392 字节：三轴各 128 字节大端补码，加 8 字节编号
 * </pre>
 *
 * <p>
 * 记录区先按三轴排序再压缩，因此文件内容与插入顺序无关。三轴用 {@link Int1024#toBytes()} 与
 * {@link Int1024#of(byte[])}，与 ssa 条目里的坐标同一个编码，逐位互逆。
 *
 * <p>
 * 六条不变式：
 *
 * <ol>
 * <li>编号只增，永不复用。复用会让两个区域指向同一个文件，那是不可恢复的数据污染。</li>
 * <li>{@code next} 从表里最大的编号加一推导，不另存字段。</li>
 * <li>落盘先于建文件：只有原子替换成功、{@link #ensure} 交回编号之后，调用方才建文件；替换失败时 {@link #ensure}
 * 返回 null，一个文件也不建。崩溃的后果只允许是「文件在、表里没有」，那是一个没人引用的孤儿文件。</li>
 * <li>读失败即抛，不重建。丢表等于把段数据变成不可达。</li>
 * <li>结构错一律抛 {@link SsaMappingException}，不许当作空表继续。magic、版本、文件长度、条目数与压缩长度的对齐、
 * 解压后的长度，五道判据都过才逐条读。</li>
 * <li>旧档以明确异常失败。{@code mapping.json} 在而没有 {@code mapping.dat} 时抛，不静默重建：重建会让编号从零
 * 开始，新编号撞上旧的 {@code s.<id>.ssa}，读到坐标不符即当作未命中重生成，然后覆写旧文件。</li>
 * </ol>
 *
 * <p>
 * 线程：只在服务端主线程读写。它是文件命名的唯一权威，IO 线程只接收算好的路径。
 */
public final class SimulationFileIndex {

    private static final String FILE_NAME = "mapping.dat";

    /** 旧档的表名。只用于拒绝，不读它。 */
    private static final String LEGACY_FILE_NAME = "mapping.json";

    private static final int MAGIC = 0x5353414D;

    private static final int FORMAT_VERSION = 1;

    /** 头部：magic、版本、条目数、压缩长度，各 4 字节。 */
    private static final int HEADER_BYTES = 16;

    /** 一轴坐标的字节数，与 ssa 条目的 X、Y、Z 同宽。 */
    private static final int COORD_BYTES = 128;

    /** 一条记录：三轴坐标加编号。 */
    private static final int RECORD_BYTES = COORD_BYTES * 3 + Long.BYTES;

    /** lz4 单块的最大膨胀倍数，用来在分配之前挡住坏条目数。 */
    private static final long MAX_EXPANSION = 255L;

    private static final String ID_FORMAT = "%016x";

    /** 记录区的排序，三轴依次比。 */
    private static final Comparator<Region> REGION_ORDER = (a, b) -> {
        int compared = a.x().compareTo(b.x());
        if (compared != 0) {
            return compared;
        }
        compared = a.y().compareTo(b.y());
        return compared != 0 ? compared : a.z().compareTo(b.z());
    };

    /**
     * 一个 ssa 文件覆盖的区域：三段都是段号右移五位的结果。
     *
     * <p>
     * 相等与哈希只看三轴。坐标是 1024 位值，哈希在构造时算一次。
     */
    public static final class Region {

        private final Int1024 x;

        private final Int1024 y;

        private final Int1024 z;

        private final int hash;

        public Region(Int1024 x, Int1024 y, Int1024 z) {
            this.x = Objects.requireNonNull(x);
            this.y = Objects.requireNonNull(y);
            this.z = Objects.requireNonNull(z);
            int hash = this.x.hashCode();
            hash = 31 * hash + this.y.hashCode();
            this.hash = 31 * hash + this.z.hashCode();
        }

        public Int1024 x() {
            return this.x;
        }

        public Int1024 y() {
            return this.y;
        }

        public Int1024 z() {
            return this.z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Region other)) {
                return false;
            }
            return this.hash == other.hash && this.x.equals(other.x) && this.y.equals(other.y)
                    && this.z.equals(other.z);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    /** 每维度一份，按维度目录缓存。 */
    private static final Map<Path, SimulationFileIndex> INSTANCES = new HashMap<>();

    private final Path dir;

    private final Path file;

    private final Path tmp;

    /** 区域到编号，保插入顺序，落盘时按三轴排序写出。 */
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
        Path dir = SimulationSectionIO.dimensionDir(level);
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
        if (this.next < 0L) {
            throw new SsaMappingException("farlands: ssa mapping id space exhausted at " + this.file);
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
     *
     * <p>
     * 旧格式的表存在而新表不存在时抛：那是不可兼容的旧档，静默重建会覆写旧 ssa。
     */
    private static SimulationFileIndex load(Path dir) {
        SimulationFileIndex index = new SimulationFileIndex(dir);
        if (!Files.exists(index.file)) {
            Path legacy = dir.resolve(LEGACY_FILE_NAME);
            if (Files.exists(legacy)) {
                throw new SsaMappingException("farlands: ssa mapping is in the removed json format at " + legacy
                        + ", delete it with the old ssa files or start a new world");
            }
            return index;
        }
        long size;
        try {
            size = Files.size(index.file);
        } catch (IOException e) {
            throw new SsaMappingException("farlands: failed to stat ssa mapping " + index.file, e);
        }
        if (size < HEADER_BYTES) {
            throw new SsaMappingException("farlands: ssa mapping is shorter than its header at " + index.file);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(index.file);
        } catch (IOException e) {
            throw new SsaMappingException("farlands: failed to read ssa mapping " + index.file, e);
        }
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES);
        int magic = header.getInt();
        if (magic != MAGIC) {
            throw new SsaMappingException("farlands: ssa mapping has no SSAM magic at " + index.file);
        }
        int version = header.getInt();
        if (version != FORMAT_VERSION) {
            throw new SsaMappingException("farlands: ssa mapping version " + version + " is not "
                    + FORMAT_VERSION + " at " + index.file);
        }
        int count = header.getInt();
        int payloadLength = header.getInt();
        if (count < 0 || payloadLength < 0) {
            throw new SsaMappingException("farlands: ssa mapping has a negative count or length at " + index.file);
        }
        if (size != (long) HEADER_BYTES + payloadLength) {
            throw new SsaMappingException("farlands: ssa mapping length " + size + " does not match its header "
                    + ((long) HEADER_BYTES + payloadLength) + " at " + index.file);
        }
        byte[] records = decodeRecords(bytes, payloadLength, count, index.file);
        ByteBuffer buffer = ByteBuffer.wrap(records);
        for (int i = 0; i < count; i++) {
            byte[] rawX = new byte[COORD_BYTES];
            byte[] rawY = new byte[COORD_BYTES];
            byte[] rawZ = new byte[COORD_BYTES];
            buffer.get(rawX);
            buffer.get(rawY);
            buffer.get(rawZ);
            long id = buffer.getLong();
            Region region = new Region(Int1024.of(rawX), Int1024.of(rawY), Int1024.of(rawZ));
            if (id < 0L || id == Long.MAX_VALUE) {
                // 编号高位置位或已到 long 上界即视作空间耗尽：再加一就回绕，会撞上已发出的号。
                throw new SsaMappingException("farlands: ssa mapping id space exhausted at " + index.file);
            }
            String text = String.format(ID_FORMAT, id);
            if (index.byId.putIfAbsent(text, region) != null) {
                throw new SsaMappingException("farlands: duplicate ssa mapping id " + text + " in " + index.file);
            }
            if (index.byRegion.put(region, text) != null) {
                throw new SsaMappingException("farlands: duplicate ssa mapping region at entry " + i + " in "
                        + index.file);
            }
            if (id + 1L > index.next) {
                index.next = id + 1L;
            }
        }
        return index;
    }

    /**
     * 解压记录区，并核对长度。
     *
     * <p>
     * 先在分配之前挡住坏条目数：lz4 单块的膨胀上限是 255 倍，超过这个上界就说明头部是坏的，不能照着它分配。
     * 解压之后还要求恰好读完，多一个字节都算坏：条目数被改小时，多出来的记录会落在后面。
     */
    private static byte[] decodeRecords(byte[] file, int payloadLength, int count, Path path) {
        long expected = (long) count * RECORD_BYTES;
        if (expected > Integer.MAX_VALUE || expected > MAX_EXPANSION * payloadLength) {
            throw new SsaMappingException("farlands: ssa mapping count " + count + " does not fit "
                    + payloadLength + " compressed bytes at " + path);
        }
        byte[] records = new byte[(int) expected];
        try {
            LZ4BlockInputStream compressed = new LZ4BlockInputStream(
                    new ByteArrayInputStream(file, HEADER_BYTES, payloadLength));
            try {
                DataInputStream in = new DataInputStream(compressed);
                in.readFully(records);
                if (in.read() != -1) {
                    throw new SsaMappingException("farlands: ssa mapping holds more records than its count at "
                            + path);
                }
            } finally {
                compressed.close();
            }
        } catch (IOException e) {
            throw new SsaMappingException("farlands: failed to decompress ssa mapping " + path, e);
        }
        return records;
    }

    /**
     * 全量重写，先写临时文件并刷盘，再原子替换。
     *
     * <p>
     * 原子替换不是所有文件系统都支持，网络盘上会抛 {@code AtomicMoveNotSupportedException}，那时退回非原子
     * 替换。非原子那一支的窗口是「旧文件已删、新文件未就位」，只在替换瞬间崩溃时出现。
     */
    private void save() {
        List<Region> ordered = new ArrayList<>(this.byRegion.keySet());
        ordered.sort(REGION_ORDER);
        byte[] payload = encodeRecords(ordered);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC);
        header.putInt(FORMAT_VERSION);
        header.putInt(ordered.size());
        header.putInt(payload.length);
        ByteBuffer content = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        content.put(header.array()).put(payload).flip();
        try {
            Files.createDirectories(this.dir);
            try (FileChannel channel = FileChannel.open(this.tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (content.hasRemaining()) {
                    channel.write(content);
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

    /** 按给定顺序把区域与编号摊成定长记录，再整段 lz4。 */
    private byte[] encodeRecords(List<Region> ordered) {
        ByteBuffer raw = ByteBuffer.allocate(ordered.size() * RECORD_BYTES);
        for (Region region : ordered) {
            raw.put(region.x().toBytes());
            raw.put(region.y().toBytes());
            raw.put(region.z().toBytes());
            raw.putLong(Long.parseUnsignedLong(this.byRegion.get(region), 16));
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, raw.capacity() / 4));
            LZ4BlockOutputStream compressed = new LZ4BlockOutputStream(bytes);
            compressed.write(raw.array());
            compressed.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("farlands: failed to encode ssa mapping " + this.file, e);
        }
    }
}
