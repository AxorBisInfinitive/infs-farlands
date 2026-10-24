package com.inf.farlands.client;

/**
 * 让某个 chunk 及其邻域的方块染色缓存失效。由 {@code ClientLevelMixin} 注入到 {@code ClientLevel}。
 *
 * <p>方块颜色按列缓存在 {@code ClientLevel.tintCaches} 里，缓存只在这两处失效：chunk 加载完成时
 * 清该 chunk 及其 3×3 邻域，以及整表重载时全清。也就是说，一个 chunk 的数据晚于邻居的染色查询到达时，
 * 邻居那几列已经按「对侧是空气」算过色并缓存下来，之后没有任何事件会清它。
 *
 * <p>本 port 的段数据正是晚到的：chunk 先以空壳到达，真段与真群系由 §5 段包补。所以 §5 应用数据时
 * 必须补一次失效，否则边缘那几列的颜色要等到整表重载（F3+A）才会变。
 *
 * <p>失效只影响下一次编译取色，本身不触发重建。
 */
public interface TintInvalidator {

    /** 清该 chunk 与其 3×3 邻域的染色缓存。 */
    void invalidateTintAround(int chunkX, int chunkZ);
}
