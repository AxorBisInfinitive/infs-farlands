package com.inf.farlands.client.render;

import love.crysh.long2.ints.Int1024;

/**
 * 暴露 {@code Frustum} 的相机锚点，供拷贝时同步。
 *
 * <p>
 * 由 {@code render.FrustumMixin} 实现，与 {@code WindowedChunk} 挂 {@code ChunkAccess} 同形。
 * {@code Frustum.set(Frustum)} 的原版体只拷矩阵、{@code viewVector} 与三个相机分量，而本 port 把
 * 那三个分量改成了「相对锚点的偏移」，所以锚点要单独拷一次，见那个 mixin 的 {@code set} 注入。
 */
public interface AnchoredFrustum {

    Int1024 farlands$anchorX();

    Int1024 farlands$anchorY();

    Int1024 farlands$anchorZ();

    /**
     * 直接写入精确帧：锚点是 1280 位坐标的精确下取整，局部量是 {@code [0,1)} 内的小数。
     *
     * <p>
     * vanilla 只提供 {@code prepare(double, double, double)}，三个入参是相机位置的 double 投影，锚点因此带着投影的
     * 量化误差：`1e18` 处 ulp 是 128，`8.9e307` 处约 `2e292`。绘制与 pick 用的是精确帧，两边差这些量就是剔除与画面
     * 对不上的来源。本入口由相机在 {@code prepareCullFrustum} 之后调用，覆盖那次 double 写入的帧。
     */
    void farlands$prepareExact(Int1024 anchorX, Int1024 anchorY, Int1024 anchorZ,
            double localX, double localY, double localZ);

    /**
     * 段盒的可见性判定。入参是段原点的 1024 位方块坐标，盒边长 {@code size}。
     *
     * <p>
     * 相对量由视锥自己算，调用方不自家算：裁剪用的是「相机后退到 8 格立方完全入锥」之后的副本，那个副本的相机点
     * 与外部相机点差着若干格，用外部点算出来的相对量与它对不上，后退等于没做。
     *
     * <p>
     * 判据与 {@code Frustum.isVisible} 相同：{@code FrustumIntersection.intersectAab} 返回 INSIDE 或
     * INTERSECT 即可见。模拟之地的绝对坐标进 double 会丢掉段尺寸，所以那边的剔除必须走这个入口。
     */
    boolean farlands$isVisibleSection(Int1024 originX, Int1024 originY, Int1024 originZ, int size);
}
