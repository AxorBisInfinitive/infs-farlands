package com.inf.farlands.client.integrate.modernui;

/**
 * 滚动容器上的引擎挂载点，由 {@code ScrollViewport} 实现。
 */
public interface SmoothScrollable {

    /** 当前引擎，未挂载为 null。 */
    SmoothScrollEngine farlands$engine();

    void farlands$engine(SmoothScrollEngine engine);
}
