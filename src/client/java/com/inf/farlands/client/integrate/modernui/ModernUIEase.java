package com.inf.farlands.client.integrate.modernui;

import com.inf.farlands.InfsFarlands;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractScrollArea;

/**
 * ModernUI 平滑滚动的接入门面。
 *
 * <p>
 * 本类与注入点都不得出现 ModernUI 的类型：没装那个模组时它们照常加载与校验，实现类只在门控为真且真的
 * 被调用时才需要解析。这与本仓库 Sodium 那一层同规，见 {@code SodiumWindowSync}。
 *
 * <p>
 * 门控三态：{@code loaded} 记「装了没有、且它自己的平滑开关开着」，{@code disabled} 记「已经失败过、
 * 以后不再用」，{@code warned} 保证只警告一次。
 *
 * <p>
 * 停用是对已挂载引擎的调用一并跳过，不只是不再新建挂载：任何一处抛 Throwable 之后，{@link #tick}、
 * {@link #wheel} 与 {@link #offset} 都返回中性值，滚轮落回 vanilla 的步进。只停新挂载是不够的，那样已
 * 挂载的引擎仍会被每帧调用，而它的补间目标仍会吃掉滚轮却不再产生位移。
 *
 * <p>
 * 引擎的四个调用都从这里过一层 try，注入点因此不必各自兜异常。
 */
public final class ModernUIEase {

    /** 门控结果，null 表示尚未查过。 */
    private static Boolean loaded;

    /** 失败过就整体停用：不再新建挂载，也不再调用已挂载的引擎。 */
    private static boolean disabled;

    private static boolean warned;

    private ModernUIEase() {
    }

    /** 是否启用：装了 ModernUI 且它自己的平滑开关是开的。 */
    public static boolean available() {
        if (disabled) {
            return false;
        }
        Boolean value = loaded;
        if (value == null) {
            value = probe();
            loaded = value;
        }
        return value;
    }

    /**
     * 给一个滚动区挂上引擎。门控为假、或该实例没有宿主接口时什么都不做，行为与不装 ModernUI 时相同。
     */
    public static void attach(AbstractScrollArea area) {
        if (!(area instanceof SmoothScrollable host) || !available()) {
            return;
        }
        try {
            host.farlands$engine(ModernUIEaseImpl.create(area));
        } catch (Throwable t) {
            fail("attach", t);
        }
    }

    /** 推进补间。未启用或引擎为空都不做。 */
    public static void tick(SmoothScrollEngine engine) {
        if (disabled || engine == null) {
            return;
        }
        try {
            engine.tick();
        } catch (Throwable t) {
            fail("tick", t);
        }
    }

    /** 接一次滚轮，返回是否接管。未启用或引擎为空都返回假，调用方交回 vanilla。 */
    public static boolean wheel(SmoothScrollEngine engine, double scrollY) {
        if (disabled || engine == null) {
            return false;
        }
        try {
            return engine.wheel(scrollY);
        } catch (Throwable t) {
            fail("wheel", t);
            return false;
        }
    }

    /** 绘制期的额外平移量。未启用、引擎为空或出错都是零。 */
    public static float offset(SmoothScrollEngine engine) {
        if (disabled || engine == null) {
            return 0.0F;
        }
        try {
            return engine.offset();
        } catch (Throwable t) {
            fail("offset", t);
            return 0.0F;
        }
    }

    /**
     * 首次查询：先判模组在不在，再问它自己的平滑开关。两次失败都降级，且实现类的加载失败也在这一层
     * 被兜住。
     */
    private static boolean probe() {
        if (!FabricLoader.getInstance().isModLoaded("modernui")) {
            return false;
        }
        try {
            return ModernUIEaseImpl.smoothScrollingEnabled();
        } catch (Throwable t) {
            fail("probe", t);
            return false;
        }
    }

    private static void fail(String what, Throwable t) {
        disabled = true;
        if (!warned) {
            warned = true;
            InfsFarlands.LOGGER.warn("Modern UI smooth scrolling disabled at {}", what, t);
        }
    }
}
