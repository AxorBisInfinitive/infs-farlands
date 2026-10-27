package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.inf.farlands.util.hash.HashMath;
import com.mojang.serialization.MapCodec;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * beta 1.7.3 的 finalDensity，按 4x8x4 cell 取 8 角密度并做三线性插值。
 *
 * <p>每个 NoiseChunk 新建一份，禁止共享：8 角缓存、网格点缓存与温度湿度缓存都是实例状态。密度
 * 依赖所在 biome 的温度与湿度，两者任一变就清网格点缓存。
 *
 * <p>温度湿度取自 biome。温度走公开的 getBaseTemperature。湿度没有公开取数，按名反射读私有的
 * climateSettings 字段与其 downfall 方法，只在 biome 变化时调用一次。
 */
public class BetaDensityFunction implements DensityFunction.SimpleFunction {

    private static final double MAX = 2000;
    private static final double MIN = -2000;

    /** 海平面 63 的 quart Y。beta 按列取温度湿度，固定在海平面那一层。 */
    private static final int SEA_LEVEL_QUART = QuartPos.fromBlock(63);

    private static final Field F_CLIMATE_SETTINGS;
    private static final Method M_DOWNFALL;

    static {
        try {
            F_CLIMATE_SETTINGS = Biome.class.getDeclaredField("climateSettings");
            F_CLIMATE_SETTINGS.setAccessible(true);
            M_DOWNFALL = F_CLIMATE_SETTINGS.getType().getDeclaredMethod("downfall");
            M_DOWNFALL.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: reflection on Biome climate settings failed", e);
        }
    }

    private final BetaTerrainNoise noise;
    private final boolean topFade;

    private int cachedCx = Integer.MIN_VALUE;
    private int cachedCy;
    private int cachedCz;
    private final double[] cachedCorners = new double[8];
    private double cachedTemp;
    private double cachedHum;

    /**
     * 本段用的群系布局，首次用到时从上下文取一次。
     *
     * <p>
     * 密度实例由 {@code createFinalDensity} 按段新建，所以缓存粒度是每段；布局取的是 biome 族的
     * {@code layout}，源与采样器同源，温度湿度因此与世界里的群系同一颗种子。
     */
    private BiomeSystem.BiomeLayout biomeLayout;

    private final Long2DoubleOpenHashMap pointCache = new Long2DoubleOpenHashMap();
    private final Long2DoubleOpenHashMap sample3Cache = new Long2DoubleOpenHashMap();
    private final Long2DoubleOpenHashMap sample4Cache = new Long2DoubleOpenHashMap();

    public BetaDensityFunction(BetaTerrainNoise noise, boolean topFade) {
        this.noise = noise;
        this.topFade = topFade;
        this.pointCache.defaultReturnValue(Double.NaN);
        this.sample3Cache.defaultReturnValue(Double.NaN);
        this.sample4Cache.defaultReturnValue(Double.NaN);
    }

    @Override
    public double compute(FunctionContext context) {
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();

        int cx0 = Math.floorDiv(x, 4);
        int cy0 = Math.floorDiv(y, 8);
        int cz0 = Math.floorDiv(z, 4);

        if (cx0 != cachedCx || cy0 != cachedCy || cz0 != cachedCz) {
            cachedCx = cx0;
            cachedCy = cy0;
            cachedCz = cz0;

            updateClimate(x, z);

            cachedCorners[0] = pointDensity(cx0, cy0, cz0);
            cachedCorners[1] = pointDensity(cx0 + 1, cy0, cz0);
            cachedCorners[2] = pointDensity(cx0, cy0 + 1, cz0);
            cachedCorners[3] = pointDensity(cx0 + 1, cy0 + 1, cz0);
            cachedCorners[4] = pointDensity(cx0, cy0, cz0 + 1);
            cachedCorners[5] = pointDensity(cx0 + 1, cy0, cz0 + 1);
            cachedCorners[6] = pointDensity(cx0, cy0 + 1, cz0 + 1);
            cachedCorners[7] = pointDensity(cx0 + 1, cy0 + 1, cz0 + 1);
        }

        double fx = (double) (x - cx0 * 4) / 4.0;
        double fy = (double) (y - cy0 * 8) / 8.0;
        double fz = (double) (z - cz0 * 4) / 4.0;

        return lerp3(cachedCorners[0], cachedCorners[1], cachedCorners[2], cachedCorners[3],
                cachedCorners[4], cachedCorners[5], cachedCorners[6], cachedCorners[7], fx, fy, fz);
    }

    /** biome 变化时更新温度湿度并清网格点缓存。上下文缺席即抛，不再退化成温度湿度 0。 */
    private void updateClimate(int x, int z) {
        BiomeSystem.BiomeLayout layout = this.biomeLayout;
        if (layout == null) {
            ServerLevel level = TerrainSystemContext.get();
            layout = ((LevelSystems) level).biomeSystem().layout(level);
            this.biomeLayout = layout;
        }
        Holder<Biome> holder = layout.source().getNoiseBiome(
                QuartPos.fromBlock(x), SEA_LEVEL_QUART, QuartPos.fromBlock(z), layout.sampler());
        Biome biome = holder.value();
        double t = biome.getBaseTemperature();
        double h = downfall(biome);
        if (t != cachedTemp || h != cachedHum) {
            cachedTemp = t;
            cachedHum = h;
            pointCache.clear();
        }
    }

    /** 反射读 Biome.ClimateSettings.downfall。该 record 非公开，只能按名取。prelim 也用它，所以是包私有。 */
    static float downfall(Biome biome) {
        try {
            return ((Float) M_DOWNFALL.invoke(F_CLIMATE_SETTINGS.get(biome))).floatValue();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: failed to read Biome.downfall", e);
        }
    }

    /** 单个网格点密度。同 (cx,cy,cz) 且同温度湿度时共享，相邻 cell 的公共角只算一次。 */
    private double pointDensity(int cx, int cy, int cz) {
        long key = HashMath.hash(cx, cy, cz);
        double v = pointCache.get(key);
        if (Double.isNaN(v)) {
            v = BetaTerrainFormula.density(cx * 4, cy * 8, cz * 4, noise, cachedTemp, cachedHum,
                    sample2D(3, cx, cz), sample2D(4, cx, cz), topFade);
            pointCache.put(key, v);
        }
        return v;
    }

    /** 通道 3 与 4 的 2D 采样，y 固定 10.0，按 (cx,cz) 缓存。 */
    private double sample2D(int channel, int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Long2DoubleOpenHashMap cache = channel == 3 ? sample3Cache : sample4Cache;
        double v = cache.get(key);
        if (Double.isNaN(v)) {
            v = noise.sample(channel, cx, 10.0, cz);
            cache.put(key, v);
        }
        return v;
    }

    private static double lerp3(double c000, double c100, double c010, double c110,
            double c001, double c101, double c011, double c111, double fx, double fy, double fz) {
        double c00 = c000 + fx * (c100 - c000);
        double c10 = c010 + fx * (c110 - c010);
        double c01 = c001 + fx * (c101 - c001);
        double c11 = c011 + fx * (c111 - c011);
        double c0 = c00 + fy * (c10 - c00);
        double c1 = c01 + fy * (c11 - c01);
        return c0 + fz * (c1 - c0);
    }

    @Override
    public double maxValue() {
        return MAX;
    }

    @Override
    public double minValue() {
        return MIN;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
