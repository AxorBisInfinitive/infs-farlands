package com.inf.farlands.terrain.system.terrain.noise.misc.SkyGirl;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

import net.minecraft.network.chat.Component;

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

    /** 声明：横竖两个间距，各自不得为零。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofInt("cellXz").define(4)
                    .check(value -> value != 0,
                            value -> Component.translatable(
                                    "createWorld.tab.infs-farlands.param.error.not_zero", "cellXz"))
                    .build(),
            SystemParamSpec.ofInt("cellY").define(8)
                    .check(value -> value != 0,
                            value -> Component.translatable(
                                    "createWorld.tab.infs-farlands.param.error.not_zero", "cellY"))
                    .build());

    private final int cellXz;
    private final int cellY;

    /**
     * 读两个间距，取值原样使用。
     *
     * <p>
     * 零值由声明层的单值检查拦下，界面逐键标红，提交前那一次检验再拦一次。这里不再复判：判据只写一处，
     * 两处各写一份迟早会漂移。
     */
    public SkyGirlNoiseSystem(SystemArgs args) {
        this.cellXz = args.getInt("cellXz");
        this.cellY = args.getInt("cellY");
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return new SkyGirlNode(this.cellXz, this.cellY);
    }
}
