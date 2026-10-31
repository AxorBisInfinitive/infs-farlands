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
}
