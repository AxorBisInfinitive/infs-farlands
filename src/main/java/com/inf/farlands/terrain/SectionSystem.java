package com.inf.farlands.terrain;

/**
 * 按段组织的地形系统：每段一次判定，段内的 4096 格由系统自己组织循环与预计算。
 *
 * <p>
 * 填充器为它走直写路径：不建 {@code NoiseChunk}，不装密度链与插值器，不取 generatorSettings 与
 * RandomState，也不设任何侧信道。系统自己写传进来的那一段，写完之后填充器扫该段一遍，把非空气块喂进两张
 * 高度图。
 *
 * <p>
 * 与 {@link BlockSystem} 同层、互斥，一个实现类只实现其一。分派顺序是 BlockSystem 优先，其次本接口，最后是
 * 密度链。
 *
 * <p>
 * 契约四条：
 *
 * <ol>
 * <li>必答。没有「这一段不是我的」这种返回值，接口因此没有返回值。</li>
 * <li>只写 {@link SectionContext#section()} 那一段。跨段写会破坏「一段一个推进单元」。</li>
 * <li>读其他段只走 {@link SectionContext#sectionAt(int)}，不得用 {@code getSection} 物化段。</li>
 * <li>不做流体结算，不读 {@code Beardifier}，不假设存在含水层。</li>
 * </ol>
 *
 * <p>
 * 实现必须无状态且纯方法：实例随 level 走，被多个 chunk 的 fill 并发共享。
 */
public interface SectionSystem extends TerrainSystem {

    /** 填一段。段对象与它的绝对基址由 {@code ctx} 给出。 */
    void fillSection(SectionContext ctx);
}
