package com.inf.farlands.simulation.int1024;

/**
 * 按段组织的地形系统，与 terrain 的 {@code SectionSystem} 同形。
 *
 * <p>
 * 填充器为它走直写路径：不建密度链、不装插值器、不取 {@code Settings}。系统自己写传进来的那一段。
 *
 * <p>
 * 契约：
 *
 * <ol>
 * <li>必答。没有「这一段不是我的」这种返回值。</li>
 * <li>只写 {@link SimulationSectionContext#section()} 那一段。跨段写会破坏「一段一个推进单元」。</li>
 * <li>读群系只走 {@link SimulationSectionContext#biomeAt}，读邻段容器只走
 * {@link SimulationSectionContext#biomesAt}。</li>
 * <li>邻段为 null 是常态，按「那一段不存在」处理，不抛。</li>
 * <li>不碰档位、光照、落盘、网络，不做流体结算。</li>
 * </ol>
 *
 * <p>
 * 实现必须无状态且纯方法：实例随 level 走，被 {@code farlands-sim} 上的多个填充任务共享。跑池线程，所以写段只能
 * 走五参 {@code setBlockState} 的 {@code false} 一档，或容器的其它无锁入口。
 */
public interface SimulationSectionSystem extends SimulationTerrainSystem {

    /** 填一段。段对象、绝对基址与邻域快照由 {@code ctx} 给出。 */
    void fillSection(SimulationSectionContext ctx);
}
