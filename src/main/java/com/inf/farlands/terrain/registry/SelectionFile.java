package com.inf.farlands.terrain.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import com.mojang.serialization.Codec;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 一份「按世界落的系统选择档案」的读写骨架。两份资料共用它：{@code systems.dat} 与
 * {@code simulation.dat}。
 *
 * <p>
 * 读自己来，不走 {@code SavedDataStorage}：它的读路径把解析失败记成日志后返回 null，上层随即新建默认对象，
 * 损坏的档案因此不会失败，而这条路要的是一律抛出。写借 {@code DataStorage}，编码用同一份 {@link Codec}，
 * 两端格式不会走偏。
 *
 * <p>
 * 除结构与编码外，其余判据都在这里：已初始化却缺档案即抛、缺档时先取暂存再取默认、按 server 解析一次。
 * 两层各自只提供自己的 {@code Codec}、默认与「还原成界面文本」那一层校验，逐条对应。
 */
public final class SelectionFile<T extends SavedData> {

    private final SavedDataType<T> type;

    private final Codec<T> codec;

    /** 每个 server 解析一次。键取 server 实例，不用「主世界是否已构造」判，后者依赖 level 的构造顺序。 */
    private final Map<MinecraftServer, T> resolved = Collections.synchronizedMap(new WeakHashMap<>());

    /** 创建世界页签的选择在 level 构造之前的暂存，取用一次即清。 */
    private volatile T staged;

    public SelectionFile(SavedDataType<T> type, Codec<T> codec) {
        this.type = type;
        this.codec = codec;
    }

    /** 档案路径，与 {@code SavedDataStorage} 的取法同式。 */
    public Path file(Path worldRoot) {
        return this.type.id().withSuffix(".dat")
                .resolveAgainst(worldRoot.resolve(LevelResource.DATA.id()));
    }

    public Path file(MinecraftServer server) {
        return this.file(server.getWorldPath(LevelResource.ROOT));
    }

    /** 严格自读。任何异常带上文件路径直穿，不回退也不新建默认。 */
    public T read(Path file) {
        Tag data = rootData(file);
        try {
            return this.codec.parse(NbtOps.INSTANCE, data).getOrThrow(IllegalStateException::new);
        } catch (RuntimeException e) {
            throw new IllegalStateException("farlands: failed to parse config " + file, e);
        }
    }

    /**
     * 该世界根目录下的档案能不能用。
     *
     * <p>文件在时判两层：读得出，以及 {@code validate} 能不能把整份内容还原成界面形态。第二层是各层自己的，
     * 覆盖「系统 id 未登记」「声明类型与盘上标签不符」这类只有还原时才暴露的错。
     *
     * <p>文件不在时看世界是否已初始化：已初始化却缺档的世界进了列表也打不开，判成不兼容；未初始化的世界按
     * 暂存或默认走，判成兼容。
     */
    public boolean compatible(Path worldRoot, Consumer<T> validate) {
        Path file = this.file(worldRoot);
        if (!Files.exists(file)) {
            return !initialized(worldRoot);
        }
        try {
            validate.accept(this.read(file));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 该 server 的档案，解析一次。 */
    public T systemsFor(MinecraftServer server, Function<MinecraftServer, T> defaultFor) {
        synchronized (this.resolved) {
            T data = this.resolved.get(server);
            if (data == null) {
                data = this.resolve(server, defaultFor);
                this.resolved.put(server, data);
            }
            return data;
        }
    }

    /** 创建世界页签的选择在 level 构造之前交给档案层。 */
    public void stageForLevelCreation(T data) {
        this.staged = data;
    }

    /** 停服清掉按 server 的解析结果与暂存。 */
    public void clearWorldState() {
        synchronized (this.resolved) {
            this.resolved.clear();
        }
        this.staged = null;
    }

    /** 解析：文件在则严格自读，不在则取暂存或默认并写回，已初始化却缺档即抛。 */
    private T resolve(MinecraftServer server, Function<MinecraftServer, T> defaultFor) {
        Path file = this.file(server);
        if (Files.exists(file)) {
            return this.read(file);
        }
        if (server.getWorldData().overworldData().isInitialized()) {
            throw new IllegalStateException("farlands: existing world has no config file " + file);
        }
        T data = this.staged;
        this.staged = null;
        if (data == null) {
            data = defaultFor.apply(server);
        }
        server.getDataStorage().set(this.type, data);
        return data;
    }

    /** 读根标签下的 {@code data}，缺失即抛。 */
    private static Tag rootData(Path file) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.defaultQuota());
        } catch (Exception e) {
            throw new IllegalStateException("farlands: failed to read config " + file, e);
        }
        Tag data = root.get("data");
        if (data == null) {
            throw new IllegalStateException("farlands: config has no data tag " + file);
        }
        return data;
    }

    /**
     * 该世界根目录下 {@code level.dat} 的 initialized 标志，缺键默认真。
     *
     * <p>与 {@code PrimaryLevelData.parse} 的 {@code input.get("initialized").asBoolean(true)} 同式。
     * 只在缺档案时才读，有档案的世界不付这次解析。读不出来按已初始化处理：那种世界的摘要本身解不出来，
     * 已经不可用，偏保守的方向不会误放行。
     */
    private static boolean initialized(Path worldRoot) {
        try {
            CompoundTag root = NbtIo.readCompressed(
                    worldRoot.resolve(LevelResource.LEVEL_DATA_FILE.id()), NbtAccounter.unlimitedHeap());
            return root.getCompoundOrEmpty("Data").getBooleanOr("initialized", true);
        } catch (Exception e) {
            return true;
        }
    }
}
