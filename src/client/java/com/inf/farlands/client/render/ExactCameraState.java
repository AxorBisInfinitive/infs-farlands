package com.inf.farlands.client.render;

/**
 * 相机帧在渲染状态上的副本，由 {@code render.CameraRenderStateMixin} 注入到 {@code CameraRenderState}。
 *
 * <p>
 * 存在的理由是消费点够不着 {@link ExactCameraOrigin}：段 UBO、选择框与实体位移都只有
 * {@code CameraRenderState}，而它身上只有那个世界量级的 {@code pos}。帧随状态走，消费点就不必再回头找
 * {@code Camera} 实例。
 *
 * <p>
 * {@link #farlands$stateValid()} 为假表示这一帧没有可用的帧，消费点必须退回 {@code pos} 的 double 运算。
 */
public interface ExactCameraState {

    /** 这一帧是否有可用的相机帧。 */
    boolean farlands$stateValid();

    /** 相机位置在 {@code axis} 轴上的锚点。 */
    long farlands$stateAnchor(int axis);

    /** 相机位置在 {@code axis} 轴上相对锚点的局部量。 */
    double farlands$stateLocal(int axis);

    /** 写入这一帧的帧。锚点与局部量同源，三轴各一组。 */
    void farlands$setState(long anchorX, long anchorY, long anchorZ,
            double localX, double localY, double localZ);

    /** 作废这一帧的帧。 */
    void farlands$clearState();
}
