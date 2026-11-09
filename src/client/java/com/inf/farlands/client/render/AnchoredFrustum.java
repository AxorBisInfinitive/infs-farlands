package com.inf.farlands.client.render;

/**
 * 暴露 {@code Frustum} 的相机锚点，供拷贝时同步。
 *
 * <p>
 * 由 {@code render.FrustumMixin} 实现，与 {@code WindowedChunk} 挂 {@code ChunkAccess} 同形。
 * {@code Frustum.set(Frustum)} 的原版体只拷矩阵、{@code viewVector} 与三个相机分量，而本 port 把
 * 那三个分量改成了「相对锚点的偏移」，所以锚点要单独拷一次，见那个 mixin 的 {@code set} 注入。
 */
public interface AnchoredFrustum {

    long farlands$anchorX();

    long farlands$anchorY();

    long farlands$anchorZ();

    /**
     * 相机相对坐标下的 AABB 可见性判定。入参是「世界减相机」，与视锥平面所在的坐标系一致。
     *
     * <p>
     * 判据与 {@code Frustum.isVisible} 相同：{@code FrustumIntersection.intersectAab} 返回 INSIDE 或
     * INTERSECT 即可见。模拟之地的绝对坐标进 double 会丢掉段尺寸，所以那边的剔除必须走这个入口，不能把
     * 绝对盒子交给 {@code isVisible}。
     */
    boolean farlands$isVisibleRelative(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ);
}
