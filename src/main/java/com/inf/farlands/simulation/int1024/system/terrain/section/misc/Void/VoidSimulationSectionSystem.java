package com.inf.farlands.simulation.int1024.system.terrain.section.misc.Void;

import com.inf.farlands.simulation.int1024.SimulationSectionContext;
import com.inf.farlands.simulation.int1024.SimulationSectionSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

/**
 * VOID 逐段系统：一个方块都不写，整段留空气。
 *
 * <p>
 * 用途是把逐段路径跑起来：地形族选它之后，管线走 BIOME 到 NOISE 到 LIGHTED，段里没有方块、群系仍是 {@code BIOME}
 * 那一档写下的值，因此能单独验「范式分派认到了逐段这一支」「邻域快照被拍下来且槽位可读」两件事，不受密度链干扰。
 *
 * <p>
 * 什么也不写是有意的：它要证明的是分派与上下文，不是内容。要验 {@code biomeAt}，把它换成一处读群系的最小实现即可。
 */
public final class VoidSimulationSectionSystem implements SimulationSectionSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidSimulationSectionSystem(SystemArgs args) {
    }

    @Override
    public void fillSection(SimulationSectionContext ctx) {
        // 什么都不写。
    }
}
