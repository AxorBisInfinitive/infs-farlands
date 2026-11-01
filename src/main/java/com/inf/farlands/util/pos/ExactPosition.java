package com.inf.farlands.util.pos;

import com.inf.long2.binaries.Binary256;

/**
 * 实体坐标的 256 位存储面，由 {@code mixin.position.EntityPositionMixin} 注入到 Entity 实现。
 *
 * <p>
 * 九个槽位分成两组：位置三轴与旧位置六轴。位置三轴的读者是覆写后的 {@code getX} 一族，旧位置六轴的读者与写者
 * 是各实体类方法体里那些直取字段的指令，两边都经 {@link #farlands$exactSlots()} 取同一个数组。
 *
 * <p>
 * 槽位是惰性建的，未写过时为 null。调用方必须把 null 当作「没有 256 位值」，回落到对应的 double 字段，因为
 * Entity 构造器在建出第一个槽位之前就会写一次位置，子类构造器也会在 {@code setPosRaw} 之前写旧位置。
 */
public interface ExactPosition {

    /** 位置三轴。 */
    int POS_X = 0;

    int POS_Y = 1;

    int POS_Z = 2;

    /** 旧位置六轴，{@code xo/yo/zo} 与 {@code xOld/yOld/zOld}。 */
    int OLD_XO = 3;

    int OLD_YO = 4;

    int OLD_ZO = 5;

    int OLD_X = 6;

    int OLD_Y = 7;

    int OLD_Z = 8;

    /** 九槽 256 位存储，首次调用时建数组。槽位常量见上。 */
    Binary256[] farlands$exactSlots();

    /**
     * 帧锚点，三轴 long。位置等于锚点加槽位里的局部偏移，局部偏移恒在 {@code [0, 1)}。
     *
     * <p>
     * 物理在帧里做：锚点承担量级，局部偏移承担精度。读者是碰撞查询，它按锚点定位段，再把世界坐标化成局部坐标。
     */
    long farlands$anchor(int axis);

    /**
     * 帧内偏移。锚点非零时落在 {@code [0, 1)}；锚点为零时它就是位置本身，量级不超过 1e12。
     *
     * <p>
     * 物理与碰撞查询用。锚点为零这一段里「局部等于世界」，vanilla 的形状与本帧的形状同源。
     */
    double farlands$local(int axis);

    /**
     * 上一 tick 到当前在 {@code partial} 刻的插值，返回值相对当前帧锚点。
     *
     * <p>
     * 插值必须在帧里做：两端的世界坐标都是 256 位值，落到 double 之前先相减，差量只有一 tick 的位移。
     * 返回值不保证落在 {@code [0, 1)}，调用方允许它为负。
     */
    double farlands$lerpLocal(int axis, float partial);
}
