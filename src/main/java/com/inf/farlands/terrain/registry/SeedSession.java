package com.inf.farlands.terrain.registry;

import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.registry.SystemsData.Arg;

/**
 * 各层 seed 参数共用的取值源与会话边界。
 *
 * <p>
 * 一次创建里所有 seed 框共用一个随机值：关键字在 {@code SystemSelectionParser.fromEmpty} 处求值，各框各求一次，
 * 共用同一个静态量，于是同一次创建里拿到的都是同一个值，而不是各抽各的。
 *
 * <p>
 * 会话边界是进页：本类把 {@link #reset()} 登记到 {@link SystemPageHooks} 上，进页时重抽，所以同一次停留里只有
 * 一个值。界面与本类之间只经过那一个公开钩子。
 *
 * <p>
 * 求值点只有客户端主线程一处，同步只是让这个静态量本身无歧义。
 */
public final class SeedSession {

    /** 本次会话共用的随机 seed，未抽过时为 null。 */
    private static volatile Long shared;

    private SeedSession() {
    }

    /** 空框即抽一次的 seed 参数声明，与 farlands 的 Cwg 那份逐字同形。 */
    public static SystemParamSpec seedParam() {
        return SystemParamSpec.ofLong("seed")
                .keyword("", () -> Arg.ofLong(next()),
                        "createWorld.tab.infs-farlands.param.seed.random")
                .build();
    }

    /** 把空框会话挂到进页钩子上。由模组登记期调用一次。 */
    public static void registerPageHook() {
        SystemPageHooks.onEnter(dimension -> reset());
    }

    /** 空框取值：首次调用时抽一个并记住，此后同值。 */
    public static long next() {
        Long current = shared;
        if (current != null) {
            return current;
        }
        synchronized (SeedSession.class) {
            if (shared == null) {
                shared = ThreadLocalRandom.current().nextLong();
            }
            return shared;
        }
    }

    /** 清掉本次会话的值，下一次空框求值重新抽。只由本类登记的那个进页动作调用。 */
    private static void reset() {
        shared = null;
    }
}
