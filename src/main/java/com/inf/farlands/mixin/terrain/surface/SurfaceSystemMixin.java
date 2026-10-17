package com.inf.farlands.mixin.terrain.surface;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 地表阶段的全高适配：列扫描按玩家窗口并集的每一段来，并丢弃 surface rule 产出的基岩。
 *
 * <p>
 * vanilla 的 {@code buildSurface} 列扫描下界取 {@code protoChunk.getMinY()}，即维度下界 -64。
 * 本 port 的地形不按维度高度生成：{@code AbstractTerrainFiller} 按窗口段构造
 * {@code NoiseSettings}，即 {@code new NoiseSettings(minSectionY * 16, …)} 这种形状，且
 * {@code DimensionType} 的 Y 界已放宽到 ±MAX_BLOCK，所以密度函数在 -64 以下继续产出、
 * fill 也照填。于是维度下界 -64 既不是地形下界，作为扫描下界就漏掉整片地形。
 *
 * <p>
 * 为什么是 {@code @Overwrite} 而不是 {@code @Redirect}：扫描区间依赖段的位置与方法内局部量，
 * 而 {@code @Redirect} 的 handler 只能拿到被调用对象，算不出这些值。26.1.2 的 class 文件也没有
 * LocalVariableTable，{@code javap -l} 零条，按名捕获局部量不可行。
 *
 * <p>
 * 扫描区间取 {@link EntitySectionWindow#ranges()} 的每一段，不取 {@code chunk.getMinY()}，也不取
 * 本 chunk 的段容器 {@code allSections}。容器是历次窗口的并集，客户端由
 * {@code releaseSectionsOutsideWindow} 收，服务端的清理又会跳过在途 chunk，所以竖直跳变之后
 * 旧窗口带仍留在容器里，那个集合的边界不由当前窗口定义。段两端先夹到
 * {@link WorldBounds#MIN_PLAYABLE_SECTION} 与 {@link WorldBounds#MAX_PLAYABLE_SECTION} 再左移四位，
 * 因为 {@link EntitySectionWindow#update} 不夹取；夹到可玩段界而不是可表示段界，是因为后者下端左移
 * 四位得 {@code Integer.MIN_VALUE}，而这条 Y 循环的下端取它就是一条走不完的递减循环。
 *
 * <p>
 * 同一对段端也是 {@code frozenOceanExtension} 复制体的循环两端。原版那条循环的下界是海平面
 * 量级的读数，起点是本 port 写在窗口 Y 上的高度图读数，两者相差可达十亿格，genPool 的线程就在
 * 里面空转。钳到段之后，循环长度与那些读数无关，恒不超过段高。
 *
 * <p>
 * <b>-64 以下被基岩填充是预期效果</b>：数据包 {@code overworld.json} 的 surface rule 第一条是
 * {@code vertical_gradient("bedrock_floor", true_at_and_below: above_bottom(0), …)}，其
 * {@code compute} 在 {@code blockY <= getMinGenY()}（= -64）时<b>无条件返回 true</b>
 * （{@code SurfaceRules:760-762}）。本 port 的地形可以延伸到 -64 以下（见上），扫描区间放宽后
 * 那一片的石头正好被刷成基岩，由它封住世界底——这是要的结果，不是缺陷。
 *
 * <p>
 * {@code SurfaceRules$Context} 与 {@code $SurfaceRule} 的 InnerClasses 属性是 protected
 * （class access_flags 实为 public，但 javac 按 InnerClasses 判断），所以这两个类型跨包不可
 * 引用。因此：Context 以 {@code Object} 持有、构造走反射（{@code Class.forName} + 缓存的
 * Constructor，且 Mixin 的 {@code @Invoker} 不支持 {@code <init>}），三个方法与
 * SurfaceRule.tryApply 改走 {@code @Invoker} 访问器（见 {@link SurfaceRules$ContextInvoker}
 * 与 {@link SurfaceRules$SurfaceRuleInvoker}），{@code ruleSource.apply} 经原始类型
 * {@code Function} 擦除调用。
 *
 * <p>
 * 与上一版本（{@code worldOverflowFix/SurfaceSystemMixin}）逐条对照后保留/调整的约束：
 * <ul>
 * <li>列扫描区间：<b>逐段取玩家窗口并集</b>。上一版本用 {@code chunk.getMinBuildHeight()}，
 * 即维度下界，会漏掉 -64 以下的地形；本文件此前先用「地表往下 maxCapIter 格」近似、后改用段容器
 * 的键跨度，两者都不是窗口。按段取、并且与冻洋分支同源之后才闭合。</li>
 * <li>基岩填充：-64 以下被刷成基岩是预期效果（见上），故不过滤返回值。上一版本当前的
 * {@code SurfaceSystemMixin} 里也没有过滤——它把 {@code endY} 压在维度下界以上，从源头
 * 避开了那片填充，但也因此漏掉了 -64 以下的地形。</li>
 * <li>写入范围：保留 {@code scanRange}——比本 port 已放宽的 {@code isInsideBuildHeight}
 * 更窄，只允许写本次扫描区间。</li>
 * <li>无锁直写：仍然必要，且必须自带。{@code @Overwrite} 之后原版 {@code SurfaceSystem$1}
 * 不再被实例化，原本负责这条路径的 {@code SurfaceSystem$1Mixin} 随之失效，所以复制体内直接
 * 写 section（{@code useLocks=false}）。{@code chunk.setBlockState} 会走带锁重载
 * （PalettedContainer.acquire），与服务端线程的 section 写并发即 CTD。</li>
 * <li>列坐标：x/z 取列坐标 {@code colCoord}，只有 y 用入参——上一版本曾误把三参全取 y，
 * 写入堆到同一相对列，地表全乱。</li>
 * <li>{@code erodedBadlandsExtension} 的列扫描下界未动：它的起点 {@code startY} 是以海平面
 * 64 为基准的恶地柱几何（上限约 160），且有 {@code height <= startY} 前置条件——极端正 Y 下
 * 整个调用不执行，极端负 Y 下循环下界是绝对的 -64、次数有界，不需要跟随地表。</li>
 * </ul>
 */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemMixin {

    /**
     * 非 {@code LevelChunk} 走进地表窗口兜底的次数，只用于一次性日志。
     *
     * <p>窗口按维度分表之后，本方法拿不到 level 时只能退回全维度并集；真实路径上 {@code buildSurface}
     * 收的都是 LevelChunk，所以这条一旦出现就说明有别的路径进来了。
     */
    private static final AtomicInteger PROTO_FALLBACK_LOGGED = new AtomicInteger();

    // ---- @Shadow：buildSurface 复制体实际使用的 SurfaceSystem 私有成员 ----

    @Shadow
    private BlockState defaultBlock;

    @Shadow
    private boolean isStone(BlockState state) {
        return false;
    }

    @Shadow
    private void erodedBadlandsExtension(BlockColumn blockColumn, int x, int z, int height, LevelHeightAccessor level) {
    }

    /** 冻洋冰山几何用到的私有成员，供下面的复制体读取。 */
    @Shadow
    @Final
    private int seaLevel;

    @Shadow
    @Final
    private NormalNoise icebergSurfaceNoise;

    @Shadow
    @Final
    private NormalNoise icebergPillarNoise;

    @Shadow
    @Final
    private NormalNoise icebergPillarRoofNoise;

    @Shadow
    @Final
    private PositionalRandomFactory noiseRandom;

    /**
     * frozenOceanExtension 的复制体，逐字取自 26.1.2 原版，只有循环两端不同。
     *
     * <p>
     * 原版循环是 {@code for (y = max(height, (int)extensionTop + 1); y >= minSurfaceLevel; y--)}。
     * 起点由方法体内的 {@code extensionTop} 算出，调用点钳不到它；下界是
     * {@code context.getMinSurfaceLevel()}，取值在表面高度量级。而本 port 的 {@code height} 是写在
     * 窗口 Y 上的高度图读数，两者相差可达十亿格。jstack 上七条 {@code farlands-gen} 全停在循环体内的
     * {@code column.getBlock}，就是这条循环。这里把两端都钳进当前这一段
     * {@code [segBot, segTop]}，循环长度因此与 {@code extensionTop}、{@code minSurfaceLevel}、
     * {@code height} 的取值无关。
     *
     * <p>
     * 写入门 {@code scanRange} 与这一段同源，所以钳制不会丢掉任何落在窗口内的写入。
     */
    @Unique
    private void farlands$frozenOceanExtension(int minSurfaceLevel, Biome biome, BlockColumn column,
            BlockPos.MutableBlockPos blockPos, int blockX, int blockZ, int height, int segBot, int segTop) {
        double iceberg = Math.min(
                Math.abs(this.icebergSurfaceNoise.getValue(blockX, 0.0, blockZ) * 8.25),
                this.icebergPillarNoise.getValue(blockX * 1.28, 0.0, blockZ * 1.28) * 15.0);
        if (!(iceberg <= 1.8)) {
            double icebergRoof = Math.abs(
                    this.icebergPillarRoofNoise.getValue(blockX * 1.17, 0.0, blockZ * 1.17) * 1.5);
            double top = Math.min(iceberg * iceberg * 1.2, Math.ceil(icebergRoof * 40.0) + 14.0);
            if (biome.shouldMeltFrozenOceanIcebergSlightly(blockPos.set(blockX, this.seaLevel, blockZ),
                    this.seaLevel)) {
                top -= 2.0;
            }

            double extensionBottom;
            if (top > 2.0) {
                extensionBottom = this.seaLevel - top - 7.0;
                top += this.seaLevel;
            } else {
                top = 0.0;
                extensionBottom = 0.0;
            }

            double extensionTop = top;
            RandomSource random = this.noiseRandom.at(blockX, 0, blockZ);
            int maxSnowDepth = 2 + random.nextInt(4);
            int minSnowHeight = this.seaLevel + 18 + random.nextInt(10);
            int snowDepth = 0;

            // 与原版唯一的差别：起点与终点都钳进这一段。
            int from = Math.min(Math.max(height, (int) extensionTop + 1), segTop);
            int to = Math.max(minSurfaceLevel, segBot);
            for (int y = from; y >= to; y--) {
                if (column.getBlock(y).isAir() && y < (int) extensionTop && random.nextDouble() > 0.01
                        || column.getBlock(y).is(Blocks.WATER) && y > (int) extensionBottom
                                && y < this.seaLevel && extensionBottom != 0.0
                                && random.nextDouble() > 0.15) {
                    if (snowDepth <= maxSnowDepth && y > minSnowHeight) {
                        column.setBlock(y, Blocks.SNOW_BLOCK.defaultBlockState());
                        snowDepth++;
                    } else {
                        column.setBlock(y, Blocks.PACKED_ICE.defaultBlockState());
                    }
                }
            }
        }
    }

    // ---- Context：类型不可引用，构造器 protected 且 @Invoker 不支持 <init> ----

    @Unique
    private static final Constructor<?> CTOR_CONTEXT;

    static {
        try {
            Class<?> contextClass = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Context");
            CTOR_CONTEXT = contextClass.getDeclaredConstructor(
                    SurfaceSystem.class, RandomState.class, ChunkAccess.class, NoiseChunk.class,
                    Function.class, Registry.class, WorldGenerationContext.class);
            CTOR_CONTEXT.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static Object newContext(SurfaceSystem system, RandomState randomState,
            ChunkAccess chunk, NoiseChunk noiseChunk, Function<BlockPos, Holder<Biome>> biomeGetter,
            Registry<Biome> biomes, WorldGenerationContext generationContext) {
        try {
            return CTOR_CONTEXT.newInstance(system, randomState, chunk, noiseChunk, biomeGetter, biomes,
                    generationContext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 复制 vanilla buildSurface，26.1.2 L66-163，5 处改动：
     * <ol>
     * <li>扫描区间逐段取玩家窗口并集：段的两端左移四位，移位前先夹 WorldBounds 的段界。</li>
     * <li>丢弃 surface rule 返回的基岩。bedrock_floor 的 vertical_gradient 在 getMinGenY()
     * 及以下全 true，不丢会盖住 -64 以下的地形。</li>
     * <li>BlockColumn.setBlock 范围：scanRange = 当前这一段，每列每段重建，匿名类数组捕获。</li>
     * <li>写入改为无锁直写 section；x/z 取列坐标 colCoord，只有 y 用入参。</li>
     * <li>Context 与 SurfaceRule 走反射/invoker，类型不可引用；冻洋分支改调本类复制体。</li>
     * </ol>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Overwrite
    public void buildSurface(
            RandomState randomState,
            BiomeManager biomeManager,
            Registry<Biome> biomes,
            boolean useLegacyRandom,
            WorldGenerationContext generationContext,
            ChunkAccess protoChunk,
            NoiseChunk noiseChunk,
            SurfaceRules.RuleSource ruleSource) {
        SurfaceSystem self = (SurfaceSystem) (Object) this;
        BlockPos.MutableBlockPos columnPos = new BlockPos.MutableBlockPos();
        ChunkPos chunkPos = protoChunk.getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        // 写入范围 scanRange，每列重建：匿名类不能捕获循环变量，用数组传递
        int[] scanRange = new int[2];
        // 写入用的列坐标：x/z 用列坐标，只有 y 用 setBlock 的入参
        int[] colCoord = new int[2];
        BlockColumn column = new BlockColumn() {
            @Override
            public BlockState getBlock(int blockY) {
                return protoChunk.getBlockState(columnPos.setY(blockY));
            }

            @Override
            public void setBlock(int blockY, BlockState state) {
                if (blockY >= scanRange[0] && blockY <= scanRange[1]) {
                    // 无锁直写 section（useLocks=false）——对齐 fill。走 chunk.setBlockState 会进
                    // 带锁重载（PalettedContainer.acquire），与服务端线程的 section 写并发即 CTD。
                    LevelChunkSection section = ((WindowedChunk) protoChunk).windowedAllSections().get(blockY >> 4);
                    if (section != null) {
                        section.setBlockState(colCoord[0] & 15, blockY & 15, colCoord[1] & 15, state, false);
                        if (!state.getFluidState().isEmpty()) {
                            // 直写绕过了 vanilla setBlockState 内部的 columnPos.setY，这里补齐
                            protoChunk.markPosForPostprocessing(columnPos.setY(blockY));
                        }
                    }
                }
            }

            @Override
            public String toString() {
                return "ChunkBlockColumn " + chunkPos;
            }
        };
        Object context = newContext(
                self, randomState, protoChunk, noiseChunk, biomeManager::getBiome, biomes, generationContext);
        // RuleSource extends Function<Context, SurfaceRule>，而 SurfaceRule 类型不可引用，
        // 故经原始类型 Function 擦除调用（返回 Object）
        Object rule = ((Function) ruleSource).apply(context);
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        // 扫描区间取玩家窗口并集的每一段，不取本 chunk 的段容器。容器是历次窗口的并集，跨度随
        // 会话历史增长，竖直跳变之后旧窗口带还留在里面，按它取跨度就是拿一个边界不由当前窗口
        // 定义的集合在取数。段两端要先夹再移位，因为 EntitySectionWindow.update 不夹取，玩家在高位时
        // c + verticalSimulationDistance 会越过段界。
        //
        // 夹到可玩段界而不是可表示段界，与 collectSegments、hasWorkInWindow、hasUnprocessed、seedSection
        // 四处判据面同宽。可表示段界下端 MIN_SECTION 左移四位正好是 Integer.MIN_VALUE，而下面的 Y 循环
        // 是 int 递减到 endY，走到 MIN_VALUE 时 y-- 会回绕到 MAX_VALUE，循环永不结束。
        int[] ranges;
        if (protoChunk instanceof LevelChunk levelChunk) {
            ranges = EntitySectionWindow.ranges(levelChunk.getLevel().dimension());
        } else {
            // 拿不到维度：退回全维度并集，并打一条限流日志。每次调用都现合并，属低频兜底。
            if (PROTO_FALLBACK_LOGGED.getAndIncrement() == 0) {
                InfsFarlands.LOGGER.warn("farlands: surface window fallback on non-LevelChunk {}",
                        protoChunk.getPos());
            }
            ranges = EntitySectionWindow.unionAllDimensions();
        }
        if (ranges.length == 0) {
            return;
        }
        int segCount = ranges.length / 2;
        int[] segBot = new int[segCount];
        int[] segTop = new int[segCount];
        for (int i = 0; i < segCount; i++) {
            int loSy = Math.max(Math.min(ranges[i * 2], WorldBounds.MAX_PLAYABLE_SECTION),
                    WorldBounds.MIN_PLAYABLE_SECTION);
            int hiSy = Math.min(Math.max(ranges[i * 2 + 1], WorldBounds.MIN_PLAYABLE_SECTION),
                    WorldBounds.MAX_PLAYABLE_SECTION);
            segBot[i] = loSy << 4;
            segTop[i] = (hiSy << 4) + 15;
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;
                int startingHeight = protoChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                colCoord[0] = blockX;
                colCoord[1] = blockZ;
                columnPos.setX(blockX).setZ(blockZ);
                Holder<Biome> surfaceBiome = biomeManager.getBiome(
                        blockPos.set(blockX, useLegacyRandom ? 0 : startingHeight, blockZ));
                if (surfaceBiome.is(Biomes.ERODED_BADLANDS)) {
                    this.erodedBadlandsExtension(column, blockX, blockZ, startingHeight, protoChunk);
                }

                int height = protoChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                for (int i = 0; i < segCount; i++) {
                    // 每段一套列状态：段与段之间没有数据，状态各自从零起，与 GenTask 逐段填充同语义。
                    int endY = segBot[i];
                    scanRange[0] = segBot[i];
                    scanRange[1] = segTop[i];
                    ((SurfaceRules$ContextInvoker) context).farlands$updateXZ(blockX, blockZ);
                    int stoneAboveDepth = 0;
                    int waterHeight = Integer.MIN_VALUE;
                    int nextCeilingStoneY = Integer.MAX_VALUE;

                    for (int y = Math.min(height, segTop[i]); y >= endY; y--) {
                        BlockState old = column.getBlock(y);
                        if (old.isAir()) {
                            stoneAboveDepth = 0;
                            waterHeight = Integer.MIN_VALUE;
                        } else if (!old.getFluidState().isEmpty()) {
                            if (waterHeight == Integer.MIN_VALUE) {
                                waterHeight = y + 1;
                            }
                        } else {
                            if (nextCeilingStoneY >= y) {
                                nextCeilingStoneY = DimensionType.WAY_BELOW_MIN_Y;

                                for (int lookaheadY = y - 1; lookaheadY >= endY - 1; lookaheadY--) {
                                    BlockState nextState = column.getBlock(lookaheadY);
                                    if (!this.isStone(nextState)) {
                                        nextCeilingStoneY = lookaheadY + 1;
                                        break;
                                    }
                                }
                            }

                            stoneAboveDepth++;
                            int stoneBelowDepth = y - nextCeilingStoneY + 1;
                            ((SurfaceRules$ContextInvoker) context).farlands$updateY(
                                    stoneAboveDepth, stoneBelowDepth, waterHeight, blockX, y, blockZ);
                            if (old == this.defaultBlock) {
                                BlockState state = ((SurfaceRules$SurfaceRuleInvoker) rule)
                                        .farlands$tryApply(blockX, y, blockZ);
                                if (state != null) {
                                    column.setBlock(y, state);
                                }
                            }
                        }
                    }

                    if (surfaceBiome.is(Biomes.FROZEN_OCEAN) || surfaceBiome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                        this.farlands$frozenOceanExtension(
                                ((SurfaceRules$ContextInvoker) context).farlands$getMinSurfaceLevel(),
                                surfaceBiome.value(), column, blockPos, blockX, blockZ, startingHeight,
                                segBot[i], segTop[i]);
                    }
                }
            }
        }
    }
}
