package com.inf.farlands.client.render;

/**
 * 相机原点的帧表示：锚点 long 加局部 double。
 *
 * <p>
 * 由 {@code client.mixin.fix.y.CameraMixin} 注入到 {@code Camera} 实现。相机位置在世界量级上是 double，
 * {@code 5e18} 处一个 ulp 是 1024，眼高与第三人称后退都会被吞掉；这里把同一份位置拆成「量级」与「精度」两段，
 * 消费侧用 {@code (origin - anchor) - local} 算相机相对偏移，全程不落回世界量级的 double。
 *
 * <p>
 * {@link #farlands$cameraValid()} 为假表示这一帧没有可用的帧表示，矿车分支就是这种情形，消费侧必须退回相机
 * 自己的 double 位置。
 */
public interface ExactCameraOrigin {

    /** 这一帧是否有可用的帧表示。 */
    boolean farlands$cameraValid();

    /** 相机位置在 {@code axis} 轴上的锚点。 */
    long farlands$cameraAnchor(int axis);

    /** 相机位置在 {@code axis} 轴上相对锚点的局部量，落在 {@code [0, 1)}。 */
    double farlands$cameraLocal(int axis);
}
