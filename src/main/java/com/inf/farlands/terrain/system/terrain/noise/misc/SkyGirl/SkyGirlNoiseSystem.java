package com.inf.farlands.terrain.system.terrain.noise.misc.SkyGirl;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * 天空网格噪声系统：全世界只在网格点上留下方块。
 *
 * <p>两个间距就是这个系统的全部参数。横竖取同一个值是立方网格，取不同的值是横向与竖向疏密不同的网格，
 * 都取 1 时退化成全实心。
 *
 * <p>本系统只决定「有没有方块」。方块类型由维度的 {@code default_block} 给；surface、carver、
 * decoration 三族会改写格点，要纯净网格就把那三族选成 VOID。
 */
public final class SkyGirlNoiseSystem implements NoiseSystem {

    /** 声明：横竖两个间距。没有关键字，也不设值域。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofInt("cellXz").define(4).build(),
            SystemParamSpec.ofInt("cellY").define(8).build());

    private final int cellXz;
    private final int cellY;

    /**
     * 读两个间距，只对 0 抛。
     *
     * <p>0 会让 {@code Math.floorMod} 抛 {@link ArithmeticException}，那条路在生成线程上，报错离配置点
     * 很远。在构造期抛，世界起不来，就能立刻指到是哪个参数。其余取值一律原样使用。
     */
    public SkyGirlNoiseSystem(SystemArgs args) {
        this.cellXz = args.getInt("cellXz");
        this.cellY = args.getInt("cellY");
        if (this.cellXz == 0 || this.cellY == 0) {
            throw new IllegalArgumentException(
                    "farlands: sky girl cell size must not be zero, got " + this.cellXz + " and " + this.cellY);
        }
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return new SkyGirlNode(this.cellXz, this.cellY);
    }

    /** 用维度噪声设置里的 cell 尺寸，本系统不自定义网格。 */
    @Override
    public int[] noiseSize() {
        return null;
    }
}
