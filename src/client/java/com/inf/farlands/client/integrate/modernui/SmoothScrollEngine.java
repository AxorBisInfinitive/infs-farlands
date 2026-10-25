package com.inf.farlands.client.integrate.modernui;

/**
 * 平滑滚动的引擎契约。
 *
 * <p>
 * 实现方是 {@code ModernUIEaseImpl}，那一层才会出现 ModernUI 的类型。本接口与宿主接口一起让视口与门面
 * 都不依赖第三方类型：没装 ModernUI 时这些类照常加载，引擎字段恒为 null。
 *
 * <p>
 * 引擎只读一个数、写一个数：视口的滚动量与内容上限都从宿主对象上取，不拦截任何方法。外部写入，例如
 * 拖动、聚焦滚动与展开上滚，由引擎按「当前值不等于自己上次写的值」发现并重新对齐，所以这里没有对齐
 * 方法。
 */
public interface SmoothScrollEngine {

    /** 推进补间并落值。绘制帧调。 */
    void tick();

    /** 接一次滚轮。返回真表示已接管，调用方吃掉这个事件。 */
    boolean wheel(double scrollY);

    /** 内容应当额外平移的像素量，用来补上被整数位置截掉的小数。 */
    float offset();
}
