package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.CounterIdentities;
import com.inf.farlands.serialize.CounterIdentity;
import com.inf.farlands.serialize.PendingBlockEntities;
import com.inf.farlands.serialize.PendingFluidPostProcess;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.CarvingMaskStorage;
import com.inf.farlands.terrain.ChunkBeardifier;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.lighting.LevelLightEngine;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;

import org.slf4j.Logger;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin
        implements WindowedChunk, CarvingMaskStorage, ChunkBeardifier, PendingBlockEntities, CounterIdentity,
        PendingFluidPostProcess {

    @Unique
    private final Map<Integer, LevelChunkSection> allSections = new ConcurrentHashMap<>();

    /**
     * 记账身份，构造末尾分配，只给服务端的 LevelChunk，0 表示未分配。不靠字段初始化器，因为
     * 目标类没有无参构造器，而赋值点就在本类的注入里。
     */
    @Unique
    private long farlands$counterId;

    @Unique
    private volatile LevelChunkSection[] windowSections = new LevelChunkSection[0];

    @Redirect(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 0)
    private void suppressSectionMismatchWarn(Logger logger, String msg, Object a, Object b) {
        // 抑制 section 数组长度不匹配的警告
    }

    @Unique
    private int windowMinY = 4;

    @Unique
    private int lastPacketMinY = Integer.MIN_VALUE;

    @Unique
    private int lastPacketMaxY = Integer.MIN_VALUE;

    @Unique
    private final IntRBTreeSet activeSectionYs = new IntRBTreeSet();

    @Unique
    private PalettedContainerFactory containerFactory;

    /**
     * carving mask 载体。26.1.2 只有单个 mask，没有 GenerationStep.Carving 分组。
     * 不用字段初始化器，懒建在 getOrCreateCarvingMask 里，因为本类没有显式构造器，@Unique 实例
     * 字段的初始化器可能被静默丢弃，读到时会是 null。
     */
    @Unique
    private CarvingMask farlandsCarvingMask;

    /**
     * 生成期待结算的流体位置，按绝对 sectionY 分组，值是该段内的打包偏移。懒建，见 farlands$fluidPostTable。
     *
     * <p>volatile：生成池线程写，服务端主线程在 postProcessGeneration 里换出。
     */
    @Unique
    private volatile Map<Integer, ShortList> farlands$fluidPostProcess;

    @Override
    public CarvingMask getCarvingMask() {
        return this.farlandsCarvingMask;
    }

    @Override
    public CarvingMask getOrCreateCarvingMask() {
        if (this.farlandsCarvingMask == null) {
            this.farlandsCarvingMask = new CarvingMask(this.getHeight(), this.getMinY());
        }
        return this.farlandsCarvingMask;
    }

    @Override
    public void setCarvingMask(CarvingMask mask) {
        this.farlandsCarvingMask = mask;
    }

    @Shadow
    protected LevelHeightAccessor levelHeightAccessor;

    @Shadow
    protected LevelChunkSection[] sections;

    @Shadow
    public abstract int getHeight();

    @Shadow
    public abstract int getMinY();

    @Override
    public PalettedContainerFactory containerFactory() {
        return this.containerFactory;
    }

    @Override
    public LevelHeightAccessor levelHeightAccessor() {
        return this.levelHeightAccessor;
    }

    @Override
    public int windowSectionYFromIndex(int idx) {
        if ((Object) this instanceof LevelChunk) {
            return this.windowMinY + idx;
        }
        return this.levelHeightAccessor.getSectionYFromSectionIndex(idx);
    }

    @Override
    public int windowSectionIndexFromY(int y) {
        if ((Object) this instanceof LevelChunk) {
            return y - this.windowMinY;
        }
        return this.levelHeightAccessor.getSectionIndexFromSectionY(y);
    }

    private int _minSection() {
        return this.levelHeightAccessor.getMinSectionY();
    }

    private int _maxSection() {
        return this.levelHeightAccessor.getMaxSectionY();
    }

    private int _maxBuild() {
        return this.levelHeightAccessor.getMaxY();
    }

    @Overwrite
    private static void replaceMissingSections(PalettedContainerFactory containerFactory,
            LevelChunkSection[] sections) {
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) {
                sections[i] = new LevelChunkSection(containerFactory);
            }
        }
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelHeightAccessor;getSectionsCount()I"))
    private void initWindowEarly(ChunkPos chunkPos, UpgradeData upgradeData, LevelHeightAccessor lha,
            PalettedContainerFactory containerFactory, long inhabitedTime, LevelChunkSection[] sectionsParam,
            BlendingData blendingData, CallbackInfo ci) {
        if (!((Object) this instanceof ProtoChunk)) {
            this.containerFactory = containerFactory;
            this.windowMinY = -4;
            buildWindow(-4, -4);
        }
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At("RETURN"))
    private void initAllSections(
            ChunkPos chunkPos,
            UpgradeData upgradeData,
            LevelHeightAccessor lha,
            PalettedContainerFactory containerFactory,
            long inhabitedTime,
            LevelChunkSection[] sectionsParam,
            BlendingData blendingData,
            CallbackInfo ci) {
        this.containerFactory = containerFactory;
        // 身份只发给服务端的 LevelChunk：ProtoChunk 的段由它变成 LevelChunk 时那次统一扫补记，
        // 客户端 chunk 不参与记账。判端读入参 lha，不读 this.level，后者在 super 之内必为 null。
        if ((Object) this instanceof LevelChunk && lha instanceof Level level && !level.isClientSide()) {
            this.farlands$counterId = CounterIdentities.register();
        }
        if (sectionsParam != null) {
            for (int i = 0; i < sectionsParam.length; i++) {
                if (sectionsParam[i] != null) {
                    int sy = lha.getSectionYFromSectionIndex(i);
                    this.allSections.put(sy, sectionsParam[i]);
                    addActiveSection(sy);
                }
            }
        }
        buildDefaultWindow();
    }

    @Override
    public long counterId() {
        return this.farlands$counterId;
    }

    private void buildDefaultWindow() {
        if ((Object) this instanceof ProtoChunk) {
            buildWindow(4, 4);
        } else {
            buildWindow(_minSection(), _minSection());
        }
    }

    @Override
    public void buildWindow(int sectionYMin, int sectionYMax) {
        if (sectionYMin > sectionYMax) {
            return;
        }
        if (sectionYMin == this.windowMinY && sectionYMax == this.getWindowMaxY()) {
            return;
        }
        int count = sectionYMax - sectionYMin + 1;
        this.windowMinY = sectionYMin;
        LevelChunkSection[] win = new LevelChunkSection[count];
        for (int sy = sectionYMin; sy <= sectionYMax; sy++) {
            int idx = windowSectionIndexFromY(sy);
            LevelChunkSection s = this.getSection(idx);
            win[sy - sectionYMin] = s;
            try {
                LevelChunkSection[] arr = this.sections;
                if (idx >= 0 && idx < arr.length && arr[idx] != s) {
                    arr[idx] = s;
                }
            } catch (Exception ignored) {
            }
        }
        this.windowSections = win;
    }

    @Override
    public int getWindowMinY() {
        return this.windowMinY;
    }

    @Override
    public int getWindowMaxY() {
        return this.windowMinY + this.windowSections.length - 1;
    }

    /**
     * 释放窗口加 margin 之外的段。窗口移动的收尾，只在客户端跑：服务端的段释放必须先落盘，
     * 归 fsa 生命周期的脏段预算与提交顺序管，这里碰它会丢未写盘的数据。
     *
     * <p>先收集再删，避免遍历中改集合。段连同 activeSectionYs 条目、脏标记与客户端光照层
     * 一起放掉；只删 allSections 而不删光照层等于没释放，层仍按段各留 2048 字节。
     */
    @Override
    public void releaseSectionsOutsideWindow(int margin) {
        if (!((Object) this instanceof LevelChunk lc)) {
            return;
        }
        Level level = lc.getLevel();
        if (level == null || !level.isClientSide()) {
            return;
        }
        int min = this.getWindowMinY() - margin;
        int max = this.getWindowMaxY() + margin;
        List<Integer> stale = null;
        for (Integer sy : this.allSections.keySet()) {
            if (sy < min || sy > max) {
                if (stale == null) {
                    stale = new ArrayList<>();
                }
                stale.add(sy);
            }
        }
        if (stale == null) {
            return;
        }
        LevelLightEngine lightEngine = level.getLightEngine();
        FarLandsLightEngine farlands = lightEngine instanceof FarLandsLightEngine fle ? fle : null;
        ChunkPos cpos = lc.getPos();
        for (int sy : stale) {
            this.allSections.remove(sy);
            this.removeActiveSection(sy);
            this.clearSectionDirty(sy);
            if (farlands != null) {
                SectionPos pos = SectionPos.of(cpos, sy);
                farlands.removeSectionData(LightLayer.BLOCK, pos);
                farlands.removeSectionData(LightLayer.SKY, pos);
            }
        }
    }

    @Override
    public int lastPacketMinY() {
        return this.lastPacketMinY;
    }

    @Override
    public int lastPacketMaxY() {
        return this.lastPacketMaxY;
    }

    @Override
    public void setLastPacketWindow(int minY, int maxY) {
        this.lastPacketMinY = minY;
        this.lastPacketMaxY = maxY;
    }

    @Override
    public void markSectionDirty(int sectionY) {
        if ((Object) this instanceof LevelChunk lc) {
            SectionStage.markDirty(lc, sectionY);
        }
    }

    @Override
    public boolean isSectionDirty(int sectionY) {
        return (Object) this instanceof LevelChunk lc && SectionStage.isDirty(lc, sectionY);
    }

    @Override
    public void clearSectionDirty(int sectionY) {
        if ((Object) this instanceof LevelChunk lc) {
            SectionStage.clearDirty(lc, sectionY);
        }
    }

    @Override
    public void addActiveSection(int sectionY) {
        synchronized (this.activeSectionYs) {
            this.activeSectionYs.add(sectionY);
        }
    }

    @Override
    public void removeActiveSection(int sectionY) {
        synchronized (this.activeSectionYs) {
            this.activeSectionYs.remove(sectionY);
        }
    }

    @Override
    public void forEachOutsideWindows(int margin, IntConsumer consumer) {
        IntSet collected = new IntOpenHashSet();
        // 窗口按维度分表：调用点只有服务端清理，走 LevelChunk；拿不到维度时退回全维度并集。
        int[] ranges = (Object) this instanceof LevelChunk lc
                ? EntitySectionWindow.ranges(lc.getLevel().dimension())
                : EntitySectionWindow.unionAllDimensions();
        synchronized (this.activeSectionYs) {
            for (int i = 0; i < ranges.length; i += 2) {
                int min = ranges[i];
                int max = ranges[i + 1];
                IntIterator it = this.activeSectionYs.headSet(min - margin).iterator();
                while (it.hasNext()) {
                    collected.add(it.nextInt());
                }
                it = this.activeSectionYs.tailSet(max + margin + 1).iterator();
                while (it.hasNext()) {
                    collected.add(it.nextInt());
                }
            }
        }
        collected.forEach(consumer);
    }

    @Override
    public Map<Integer, LevelChunkSection> windowedAllSections() {
        return this.allSections;
    }

    /**
     * 结构性地形适配数据。volatile：主线程在存在流程里写，farlands-gen 在生成任务里读，两者之间
     * 只有 GenQueue 的 CHM 与 QUEUE 监视器提供 happens-before，标上以免将来入队路径改动时静默失效。
     */
    @Unique
    private volatile Beardifier farlandsBeardifier;

    @Override
    public void setBeardifier(Beardifier beardifier) {
        this.farlandsBeardifier = beardifier;
    }

    /**
     * 是否已设 {@code Beardifier}。生成侧的门读它，没有就不许 fill。
     *
     * <p>判据落在不变量本身，而不是「有没有被登记为等结构相」那个代理，登记晚于这个 chunk 对
     * {@code latestChunk} 可见的那一刻，中间那道窗口会让只看代理的门放行，而 fill 取数即抛。
     * 字段是 volatile，池上读安全。
     */
    @Override
    public boolean hasBeardifier() {
        return this.farlandsBeardifier != null;
    }

    @Override
    public Beardifier getBeardifier() {
        Beardifier beardifier = this.farlandsBeardifier;
        if (beardifier == null) {
            throw new IllegalStateException(
                    "farlands: chunk Beardifier not set, existence flow missed the write "
                            + ((ChunkAccess) (Object) this).getPos());
        }
        return beardifier;
    }

    @Overwrite
    public LevelChunkSection[] getSections() {
        return this.windowSections;
    }

    @Overwrite
    public LevelChunkSection getSection(int index) {
        int sy = windowSectionYFromIndex(index);
        LevelChunkSection s = this.allSections.get(sy);
        if (s == null) {
            s = new LevelChunkSection(this.containerFactory);
            this.allSections.put(sy, s);
            addActiveSection(sy);
        }
        try {
            LevelChunkSection[] arr = this.sections;
            if (index >= 0 && index < arr.length) {
                arr[index] = s;
            }
            if (index >= 0 && index < this.windowSections.length) {
                this.windowSections[index] = s;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    @Overwrite
    public int getHighestFilledSectionIndex() {
        int max = -1;
        if ((Object) this instanceof ProtoChunk) {
            for (Integer sy : this.allSections.keySet()) {
                if (sy > max)
                    max = sy;
            }
            return max == -1 ? -1 : windowSectionIndexFromY(max);
        }
        int sectionsCount = this.getSections().length;
        for (Integer sy : this.allSections.keySet()) {
            int idx = windowSectionIndexFromY(sy);
            if (idx >= 0 && idx < sectionsCount && idx > max) {
                max = idx;
            }
        }
        return max;
    }

    /**
     * 群系按原始 quart Y 定位段，不 clamp 到维度范围，且缺段不物化。
     *
     * clamp 是 vanilla 为直接索引 sections 数组而设，越界即抛；本 port 直接查 allSections，
     * 任意 quart Y 都成立，clamp 只剩把范围外 Y 折到边界段的副作用。段内偏移一直用的是未
     * clamp 的 y & 3，去掉 clamp 后索引与偏移同源。
     *
     * 缺段不能走 getSection：那会把每次群系查询变成段的来源。刷怪这类按整列随机取 Y 的调用
     * 每次都能建出一个窗口外的段，而窗口外的段只靠 fsa 清理回收，追不上就是无界内存。缺段返回
     * containerFactory 的默认群系，与「建一个空段再读它的群系」逐字等价：空段的 biome 容器就是
     * new PalettedContainer<>(defaultBiome, strategy)。
     */
    @Overwrite
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        try {
            LevelChunkSection s = this.allSections.get(y >> 2);
            if (s != null) {
                return s.getNoiseBiome(x & 3, y & 3, z & 3);
            }
            if (this.containerFactory != null) {
                return this.containerFactory.defaultBiome();
            }
            // containerFactory 在构造 RETURN 赋值，此支理论不可达，保留取段兜底
            return this.getSection(windowSectionIndexFromY(QuartPos.toBlock(y) >> 4))
                    .getNoiseBiome(x & 3, y & 3, z & 3);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Getting biome");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Biome being got");
            crashreportcategory.setDetail("Location",
                    () -> CrashReportCategory.formatLocation(((ChunkAccess) (Object) this), x, y, z));
            throw new ReportedException(crashreport);
        }
    }

    @Overwrite
    public boolean isYSpaceEmpty(int startY, int endY) {
        if (startY < this.levelHeightAccessor.getMinY()) {
            startY = this.levelHeightAccessor.getMinY();
        }
        if (endY >= _maxBuild()) {
            endY = _maxBuild() - 1;
        }
        for (int i = startY; i <= endY; i += 16) {
            // 读路径不物化段：缺段即全空气，与建空段读出 hasOnlyAir 同值。
            LevelChunkSection s = this.allSections.get(i >> 4);
            if (s != null && !s.hasOnlyAir()) {
                return false;
            }
        }
        return true;
    }

    @Overwrite
    public void findBlocks(
            Predicate<BlockState> predicate,
            BiConsumer<BlockPos, BlockState> consumer) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int i = _minSection(); i <= _maxSection(); i++) {
            // 读路径不物化段：缺段即无可匹配方块。扫的是 Level 访问器的 -4..19 带，
            // 玩家在远处时这一段与窗口无关，走 getSection 会每次调用都在那里造 24 个段。
            LevelChunkSection s = this.allSections.get(i);
            if (s == null) {
                continue;
            }
            if (s.maybeHas(predicate)) {
                BlockPos origin = SectionPos.of(self.getPos(), i).origin();
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++) {
                            BlockState state = s.getBlockState(x, y, z);
                            if (predicate.test(state)) {
                                consumer.accept(mpos.setWithOffset(origin, x, y, z), state);
                            }
                        }
            }
        }
    }

    /**
     * 记一笔「这一格写完之后要结算流体」。
     *
     * <p>
     * vanilla 在 ProtoChunk 上记进 postProcessing 数组，而本 port 的 chunk 从存在流程起就是 LevelChunk，
     * 且那个数组的下标是窗口相对索引，服务端 chunk 构造时窗口只有一段，任何窗口外的段都会越界。所以这里
     * 按绝对 sectionY 另记一张表，由 LevelChunk.postProcessGeneration 的 HEAD 取走。
     *
     * <p>
     * 段号先过可玩段界：表里的段号解包时左移四位，只有落在可玩段内的才保证不越过 int。
     */
    @Overwrite
    public void markPosForPostprocessing(BlockPos pos) {
        int sectionY = pos.getY() >> 4;
        if (!WorldBounds.inSection(sectionY)) {
            return;
        }
        short packed = ProtoChunk.packOffsetCoordinates(pos);
        this.farlands$fluidPostTable().compute(sectionY, (key, list) -> {
            ShortList target = list == null ? new ShortArrayList() : list;
            target.add(packed);
            return target;
        });
    }

    /**
     * 待办表的懒建入口。
     *
     * <p>
     * 不用字段初始化器：本类没有显式构造器，@Unique 实例字段的初始化器不保证执行，读到的会是 null。
     *
     * <p>
     * 同一个 chunk 的表只有一个写者，即持有 TERRAIN 认领的那个生成任务；这里仍用双重检查加 ConcurrentHashMap
     * 的逐键 compute，因为结算在服务端主线程、记录在生成池线程，两侧都可能先到。
     */
    @Unique
    private Map<Integer, ShortList> farlands$fluidPostTable() {
        Map<Integer, ShortList> table = this.farlands$fluidPostProcess;
        if (table != null) {
            return table;
        }
        synchronized (this) {
            table = this.farlands$fluidPostProcess;
            if (table == null) {
                table = new ConcurrentHashMap<>();
                this.farlands$fluidPostProcess = table;
            }
            return table;
        }
    }

    /**
     * 换出全部待办并清表。
     *
     * <p>
     * 逐键摘除而不是整体 clear：摘下来的列表此后不会再被写，因为记录侧要在空槽上建新列表；而迭代期间新
     * 记进来的键会被漏掉，那与 vanilla 的语义一致，postProcessGeneration 一生只跑一次，晚于它的标记同样
     * 无人消费。
     */
    @Override
    public Map<Integer, ShortList> takePendingFluidPost() {
        Map<Integer, ShortList> table = this.farlands$fluidPostProcess;
        if (table == null || table.isEmpty()) {
            return Map.of();
        }
        Map<Integer, ShortList> taken = new HashMap<>();
        for (Integer sectionY : table.keySet()) {
            ShortList list = table.remove(sectionY);
            if (list != null) {
                taken.put(sectionY, list);
            }
        }
        return taken;
    }

    @Overwrite
    public void fillBiomesFromNoise(BiomeResolver resolver, Climate.Sampler sampler) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        ChunkPos cpos = self.getPos();
        int i = QuartPos.fromBlock(cpos.getMinBlockX());
        int j = QuartPos.fromBlock(cpos.getMinBlockZ());
        LevelHeightAccessor lha = this.levelHeightAccessor;
        for (int k = lha.getMinSectionY(); k < lha.getMaxSectionY(); k++) {
            LevelChunkSection s = this.getSection(windowSectionIndexFromY(k));
            if (s == null) {
                continue;
            }
            int l = QuartPos.fromSection(k);
            s.fillBiomesFromNoise(resolver, sampler, i, l, j);
        }
    }

    /**
     * 待建方块实体标签表，即声明在 ChunkAccess 上的那个字段。@Shadow 不解析继承成员，所以影子只能放在
     * 本类；只读不写，字段虽是 final 也不需要 @Mutable。
     */
    @Shadow
    protected Map<BlockPos, CompoundTag> pendingBlockEntities;

    /**
     * 把某一段已就位的待建标签晋升成实例。
     *
     * <p>
     * 读的是 vanilla 自己的表，不新增存储。先收集再调用，因为晋升会从表里摘条目；段未就位时消费门会拒绝，
     * 这一次调用便什么都不做，标签留着等该段落位后的下一轮。
     *
     * <p>
     * 那格已经不是 BE 方块的标签直接丢掉：它没有落点，留着只会在每次载入重演一条
     * {@code Failed to create block entity}。这类孤儿来自装饰期把 BE 方块换掉之后活实例没被摘除，
     * 账在写门那边，这里只负责不让它被反复消费。
     */
    @Override
    public void promotePendingBlockEntities(int sectionY) {
        if (this.pendingBlockEntities.isEmpty()) {
            return;
        }
        List<BlockPos> ready = null;
        for (BlockPos pos : this.pendingBlockEntities.keySet()) {
            if ((pos.getY() >> 4) == sectionY) {
                if (ready == null) {
                    ready = new ArrayList<>();
                }
                ready.add(pos);
            }
        }
        if (ready == null) {
            return;
        }
        ChunkAccess self = (ChunkAccess) (Object) this;
        for (BlockPos pos : ready) {
            if (!self.getBlockState(pos).hasBlockEntity()) {
                this.pendingBlockEntities.remove(pos);
                continue;
            }
            self.getBlockEntity(pos);
        }
    }
}
