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
}
