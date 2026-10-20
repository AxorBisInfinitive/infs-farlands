package com.inf.farlands.terrain.system.terrain.noise.theEnd.Layers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

import com.inf.farlands.util.hash.HashMath;
import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * 按竖直周期分层的密度节点：每个 period 高的窗口取一套自己的末地链。
 *
 * <p>
 * Y 先折成本地量再求值：{@code layer = floorDiv(y, period)}、{@code localY = y - layer * period}。
 * 层种子由本系统的 seed 与层号派生，所以每层岛形不同，而同一份配置在任何世界、任何维度都一样。
 *
 * <p>
 * 本节点对上层不透明，{@code mapAll} 不下钻：每层的链是求值时才建的，而 {@code NoiseChunk} 的
 * {@code mapAll} 在构造期就跑完了。所以调用方必须把 {@code interpolated} 包在本节点外面，由
 * {@code NoiseChunk} 按 cell 角点调用本节点，本节点再按角点的世界 Y 选层。反过来把
 * {@code interpolated} 放进每层的链里，它永远不会被 {@code NoiseChunk} 看到，退化成逐格全量求值。
 */
final class LayerDensity implements DensityFunction {

    /** 缓存层数上限。竖直视窗只有 ±17 段即 544 格，任何时刻最多两层在算。 */
    private static final int MAX_LAYERS = 16;

    private final long seed;
    private final int period;
    private final LongFunction<DensityFunction> chainFor;
    private final Map<Integer, DensityFunction> chains = new ConcurrentHashMap<>();

    LayerDensity(long seed, int period, LongFunction<DensityFunction> chainFor) {
        this.seed = seed;
        this.period = period;
        this.chainFor = chainFor;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int y = context.blockY();
        int layer = Math.floorDiv(y, this.period);
        return this.chain(layer).compute(new LocalContext(context, y - layer * this.period));
    }

    /** 按层号取链，缺失时用该层种子建一条。超上限就整表清空，链不可变所以没有生命周期问题。 */
    private DensityFunction chain(int layer) {
        DensityFunction cached = this.chains.get(layer);
        if (cached != null) {
            return cached;
        }
        if (this.chains.size() >= MAX_LAYERS) {
            this.chains.clear();
        }
        return this.chains.computeIfAbsent(layer,
                key -> this.chainFor.apply(HashMath.hash(this.seed, key, 0L)));
    }

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    /** 每层的链是懒建的，对上层不透明：不下钻，只把自己交给访问者。 */
    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
    }

    /**
     * 宽界。本节点包住的是 {@code blendDensity} 之后的滑动链，值域可以到几十，而 {@code squeeze} 在外层，
     * 所以声明值只服务区间推导，不参与取值。
     */
    @Override
    public double minValue() {
        return -1.0E30;
    }

    @Override
    public double maxValue() {
        return 1.0E30;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }

    /** 只换 Y 的上下文，其余转发。blender 不能丢，blending 生效时角点要用它。 */
    private record LocalContext(DensityFunction.FunctionContext delegate, int blockY)
            implements DensityFunction.FunctionContext {

        @Override
        public int blockX() {
            return this.delegate.blockX();
        }

        @Override
        public int blockZ() {
            return this.delegate.blockZ();
        }

        @Override
        public Blender getBlender() {
            return this.delegate.getBlender();
        }
    }
}
