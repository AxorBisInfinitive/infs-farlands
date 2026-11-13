package com.inf.farlands.terrain.system.common.overworld.Vanilla;

import com.inf.farlands.terrain.registry.SeedSession;
import com.inf.farlands.terrain.registry.SystemParamSpec;

/**
 * overworld Vanilla 五族共用的 seed 取值源。
 *
 * <p>
 * 实现在 {@link SeedSession}：模拟层与地形层共用同一份会话语义，本类只是历史入口。保留它是因为五族声明与
 * 登记点都按这个名字写，改名会牵动无关文件。
 */
public final class VanillaFamilySeed {

    private VanillaFamilySeed() {
    }

    /** 五族共用的 seed 参数声明：空框抽一次，抽到的值在本次会话内固定。 */
    public static SystemParamSpec seedParam() {
        return SeedSession.seedParam();
    }

    /** 把空框会话挂到进页钩子上。由模组登记期调用一次。 */
    public static void registerPageHook() {
        SeedSession.registerPageHook();
    }

    /** 空框取值：首次调用时抽一个并记住，此后同值。 */
    public static long next() {
        return SeedSession.next();
    }
}
