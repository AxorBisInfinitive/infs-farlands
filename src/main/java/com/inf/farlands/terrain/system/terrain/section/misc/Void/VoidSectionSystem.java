package com.inf.farlands.terrain.system.terrain.section.misc.Void;

import com.inf.farlands.terrain.SectionContext;
import com.inf.farlands.terrain.SectionSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

/**
 * VOID 段级系统：一个方块都不写，整段留空气。
 *
 * <p>
 * 用途是把逐段路径跑起来：它什么都不写，填充器那遍高度图扫描因此全空，两张 WG 图停在段底的默认值。把它选成
 * 某一维度的地形系统，其余四族照常，就能看到「地形为空、地表与雕刻无物可改」这条链是否走得通。
 */
public final class VoidSectionSystem implements SectionSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidSectionSystem(SystemArgs args) {
    }

    @Override
    public void fillSection(SectionContext ctx) {
        // 什么都不写。
    }
}
