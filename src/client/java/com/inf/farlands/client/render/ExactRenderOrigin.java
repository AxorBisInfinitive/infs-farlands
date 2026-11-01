package com.inf.farlands.client.render;

/**
 * 实体帧在渲染状态上的副本，由 {@code render.EntityRenderStateMixin} 注入到 {@code EntityRenderState}。
 *
 * <p>
 * {@code EntityRenderState.x/y/z} 是世界量级 double，保持原样：阴影的格点扫描、矿车的轨面平移与第三方
 * 渲染器都读它。这里只额外带一份帧，供需要相机相对量的消费点换算。
 *
 * <p>
 * 帧的局部量来自 {@code ExactPosition.lerpLocal}，相对当前锚点，可以是负数，量级只有一 tick 的位移。
 */
public interface ExactRenderOrigin {

    /** 这一帧是否带可用的实体帧。 */
    boolean farlands$originValid();

    /** 实体位置在 {@code axis} 轴上的锚点。 */
    long farlands$originAnchor(int axis);

    /** 实体位置在 {@code axis} 轴上相对锚点的局部量，含部分刻插值。 */
    double farlands$originLocal(int axis);

    /** 写入这一帧的帧。锚点与局部量同源，三轴各一组。 */
    void farlands$setOrigin(long anchorX, long anchorY, long anchorZ,
            double localX, double localY, double localZ);

    /** 作废这一帧的帧。 */
    void farlands$clearOrigin();
}
