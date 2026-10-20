package com.inf.farlands.terrain.system.decoration.theEnd.Vanilla;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.base.Suppliers;
import com.inf.farlands.terrain.DecorationSystem;
import com.inf.farlands.terrain.decorationFiller.DecorationRegion;
import com.inf.farlands.terrain.decorationFiller.ScopedStructureManager;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.structure.StructureBasis;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.inf.farlands.util.window.WindowedChunk;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;

import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * vanilla 装饰系统：biome json 的地物列表加结构放置，随机性取本系统的 seed。
 *
 * <p>体是 {@code ChunkGenerator.applyBiomeDecoration} 的移植体，与另四族 vanilla 实现同形：方法体在本
 * 类里，seed 就是本类的字段，不需要任何跨实例的通路，也不需要往方法体里注入。
 *
 * <p>与本仓库那个覆写体相比只有两处不同：结构分组按生成器实例缓存；biome 收集走段容器的并集，而不是
 * 窗口数组 {@code getSections()}。
 *
 * <p>{@code ChunkGenerator} 的 {@code featuresPerStep} 是私有字段且无公开入口，只有它的构造器用到，所以
 * 这里用公开的 {@code FeatureSorter.buildFeaturesPerStep} 自建一份等价的，输入与它构造器里那一行相同。
 * 同族的 {@code getWritableArea} 是私有静态方法，其体是五行纯几何，直接照搬。
 *
 * <p>区域必须是交进来的 region：它按读域交出主线程备好的句柄，而 StructureManager 必须 scope 到同一
 * 个 region，否则结构放置的取数会落到活世界的阻塞取数上。
 *
 * <p>只服务噪声生成器的维度：另四族 vanilla 实现同样只服务噪声维度，非噪声维度即使选中本系统也不该
 * 跑这一套。
 *
 * <p>无状态：两份缓存都按生成器实例或冻结后的注册表内容建立，装饰跑在 farlands-gen 上可以并发，缓存
 * 字段因此是 volatile 或由同步方法保护。
 */
public final class VanillaDecorationSystem implements DecorationSystem {

    /** 声明：seed 由五族共用，见 {@link VanillaFamilySeed}。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    private final long seed;

    /** 按生成器实例缓存的 featuresPerStep，与 ChunkGenerator 构造器里那一行同式。 */
    private ChunkGenerator featuresFor;
    private Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep;

    /** 结构按生成步分组，惰性缓存。分组只由 Structure.step() 与冻结后的注册表内容决定。 */
    private volatile Map<Integer, List<Structure>> structuresByStep;

    public VanillaDecorationSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
    }

    /** 本维度结构相的自带基座，首次调用时建一次，之后只读。 */
    private volatile StructureBasis farlandsStructureBasis;

    private StructureBasis basis(ServerLevel level) {
        StructureBasis built = this.farlandsStructureBasis;
        if (built == null) {
            built = StructureBasis.of(Level.END, this.seed, level.registryAccess());
            this.farlandsStructureBasis = built;
        }
        return built;
    }

    /**
     * 起点相用本族自带的基座：末地的群系源、噪声设置与生成器，种子取本系统的 seed。
     *
     * <p>
     * 夹在 setVanilla 与 clear 之间，因为起点取 Y 锚会在主线程上构造 NoiseChunk。哨兵让那次构造不替换
     * router 与 aquifer，高度场因此是本族 settings 在族 seed 下的场，与宿主地形族无关。
     */
    @Override
    public boolean createStructures(ServerLevel level, LevelChunk chunk) {
        if (!StructureDriver.claimStructures(level, chunk)) {
            return true;
        }
        StructureBasis built = this.basis(level);
        TerrainSystemContext.setVanilla();
        try {
            built.generator().createStructures(level.registryAccess(), built.state(),
                    level.structureManager(), chunk, level.getStructureManager(), built.dimension());
        } finally {
            TerrainSystemContext.clear();
        }
        return true;
    }

    @Override
    public void applyDecoration(ServerLevel level, LevelChunk center, DecorationRegion region) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noiseGen)) {
            return;
        }
        ChunkGenerator gen = noiseGen;
        ChunkAccess chunk = center;
        ChunkPos chunkpos = center.getPos();
        if (SharedConstants.debugVoidTerrain(chunkpos)) {
            return;
        }
        StructureManager structureManager = ScopedStructureManager.of(level, region);
        SectionPos sectionpos = SectionPos.of(chunkpos, region.getMinSectionY());
        BlockPos blockpos = sectionpos.origin();
        Registry<Structure> registry = region.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Map<Integer, List<Structure>> map = this.structureGroups(registry);
        List<FeatureSorter.StepFeatureData> list = this.featuresPerStep(gen).get();
        // 定位随机取本系统声明的 seed：装饰的落点不跟世界种子走。
        WorldgenRandom worldgenrandom = new WorldgenRandom(new XoroshiroRandomSource(this.seed));
        long i = worldgenrandom.setDecorationSeed(this.seed, blockpos.getX(), blockpos.getZ());
        ObjectArraySet<Holder<Biome>> set = new ObjectArraySet<>();
        ChunkPos.rangeClosed(sectionpos.chunk(), 1).forEach(p -> {
            ChunkAccess ca = region.getChunk(p.x(), p.z());
            for (LevelChunkSection s : getAllSections(ca)) {
                if (s == null) {
                    continue;
                }
                s.getBiomes().getAll(set::add);
            }
        });
        set.retainAll(gen.getBiomeSource().possibleBiomes());
        int j = list.size();

        try {
            Registry<PlacedFeature> registry1 = region.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
            int i1 = Math.max(GenerationStep.Decoration.values().length, j);

            for (int k = 0; k < i1; k++) {
                if (!structureManager.shouldGenerateStructures()) {
                    continue;
                }
                int l = 0;
                for (Structure structure : map.getOrDefault(k, Collections.emptyList())) {
                    worldgenrandom.setFeatureSeed(i, l, k);
                    Supplier<String> supplier = () -> registry.getResourceKey(structure).map(Object::toString)
                            .orElseGet(structure::toString);
                    try {
                        region.setCurrentlyGenerating(supplier);
                        structureManager
                                .startsForStructure(sectionpos, structure)
                                .forEach(s1 -> s1.placeInChunk(
                                        region,
                                        structureManager,
                                        gen,
                                        worldgenrandom,
                                        getWritableArea(chunk),
                                        chunkpos));
                    } catch (Exception exception) {
                        CrashReport crashreport1 = CrashReport.forThrowable(exception, "Feature placement");
                        crashreport1.addCategory("Feature").setDetail("Description", supplier::get);
                        throw new ReportedException(crashreport1);
                    }
                    l++;
                }

                if (k >= j) {
                    continue;
                }

                IntSet intset = new IntArraySet();
                for (Holder<Biome> holder : set) {
                    List<HolderSet<PlacedFeature>> list1 = holder.value().getGenerationSettings().features();
                    if (k < list1.size()) {
                        HolderSet<PlacedFeature> holderset = list1.get(k);
                        FeatureSorter.StepFeatureData d = list.get(k);
                        holderset.stream().map(Holder::value)
                                .forEach(f -> intset.add(d.indexMapping().applyAsInt(f)));
                    }
                }

                int j1 = intset.size();
                int[] aint = intset.toIntArray();
                Arrays.sort(aint);
                FeatureSorter.StepFeatureData featuresorter$stepfeaturedata = list.get(k);
                for (int k1 = 0; k1 < j1; k1++) {
                    int l1 = aint[k1];
                    PlacedFeature placedfeature = featuresorter$stepfeaturedata.features().get(l1);
                    Supplier<String> supplier1 = () -> registry1.getResourceKey(placedfeature)
                            .map(Object::toString).orElseGet(placedfeature::toString);
                    worldgenrandom.setFeatureSeed(i, l1, k);
                    try {
                        region.setCurrentlyGenerating(supplier1);
                        placedfeature.placeWithBiomeCheck(region, gen, worldgenrandom, blockpos);
                    } catch (Exception exception1) {
                        CrashReport crashreport2 = CrashReport.forThrowable(exception1, "Feature placement");
                        crashreport2.addCategory("Feature").setDetail("Description", supplier1::get);
                        throw new ReportedException(crashreport2);
                    }
                }
            }
            region.setCurrentlyGenerating(null);
        } catch (Exception exception2) {
            CrashReport crashreport = CrashReport.forThrowable(exception2, "Biome decoration");
            crashreport.addCategory("Generation").setDetail("CenterX", chunkpos.x())
                    .setDetail("CenterZ", chunkpos.z())
                    .setDetail("Decoration Seed", i);
            throw new ReportedException(crashreport);
        }
    }

    /**
     * 该生成器实例的 featuresPerStep。{@code ChunkGenerator} 私有持有它且无公开入口，这里按公开的
     * {@code FeatureSorter.buildFeaturesPerStep} 自建等价的一份，输入与它构造器里那一行相同。
     */
    private Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep(ChunkGenerator gen) {
        if (this.featuresPerStep != null && this.featuresFor == gen) {
            return this.featuresPerStep;
        }
        Supplier<List<FeatureSorter.StepFeatureData>> built = Suppliers.memoize(
                () -> FeatureSorter.buildFeaturesPerStep(List.copyOf(gen.getBiomeSource().possibleBiomes()),
                        b -> b.value().getGenerationSettings().features(), true));
        this.featuresPerStep = built;
        this.featuresFor = gen;
        return built;
    }

    /**
     * 结构按生成步分组，按实例缓存一次。
     *
     * <p>分组只由 {@link Structure#step()} 与结构注册表的内容决定，而注册表在
     * {@code BuiltInRegistries.bootStrap} 末尾冻结，之后不再变。装饰跑在 farlands-gen 上，不同 chunk 可以
     * 并发，所以缓存字段是 volatile，建的过程放同步方法里。
     */
    private synchronized Map<Integer, List<Structure>> structureGroups(Registry<Structure> registry) {
        Map<Integer, List<Structure>> cached = this.structuresByStep;
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<Structure>> built = registry.stream()
                .collect(Collectors.groupingBy(s -> s.step().ordinal()));
        this.structuresByStep = built;
        return built;
    }

    /** 段容器与窗口数组的取舍与生成侧同规：取段容器的并集，空时退回窗口数组。 */
    private static Iterable<LevelChunkSection> getAllSections(ChunkAccess ca) {
        Map<Integer, LevelChunkSection> all = ((WindowedChunk) ca).windowedAllSections();
        if (all != null && !all.isEmpty()) {
            return all.values();
        }
        return Arrays.asList(ca.getSections());
    }

    /** 可写区域的竖直范围，逐字照搬 {@code ChunkGenerator} 的私有同名方法。 */
    private static BoundingBox getWritableArea(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int targetBlockX = chunkPos.getMinBlockX();
        int targetBlockZ = chunkPos.getMinBlockZ();
        LevelHeightAccessor heightAccessor = chunk.getHeightAccessorForGeneration();
        int minY = heightAccessor.getMinY() + 1;
        int maxY = heightAccessor.getMaxY();
        return new BoundingBox(targetBlockX, minY, targetBlockZ, targetBlockX + 15, maxY, targetBlockZ + 15);
    }
}
