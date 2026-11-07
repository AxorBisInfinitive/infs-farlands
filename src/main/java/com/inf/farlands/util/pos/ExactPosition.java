package com.inf.farlands.util.pos;

import com.inf.long2.binaries.Binary256;

import net.minecraft.world.phys.AABB;

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

    /**
     * 旧位置到当前位置的位移，帧内相减，单位格。
     *
     * <p>
     * 两个世界量级的 double 直接相减会在 {@code 5e18} 处被 ulp 吞成 0 或 1024；这里先各自求锚点与局部量，
     * 锚点差用 long 算，局部差用小数算。旧位置没记过时返回 0。
     */
    double farlands$frameDelta(int axis);

    /**
     * 当前帧下的局部包围盒。坐标是帧内的，可直接喂给帧内碰撞源。
     *
     * <p>
     * {@code getBoundingBox()} 返回的是世界量级的盒，{@code 5e18} 处六个端点会塌成一个点，任何基于它的
     * 碰撞探测都恒判「无碰撞」。凡是要拿盒去探碰撞的地方，都改用这一个。
     */
    AABB farlands$localBox();

    /**
     * 该轴的精确下取整，方块格号。
     *
     * <p>
     * 与 {@link #farlands$anchor} 的区别只有一条：它不带「0 表示帧未启用」那个约定。帧未启用时它就是那个
     * double 的下取整，帧启用时它是槽位的精确下取整。所以绝对值场景用它，差值场景用锚点与局部量。
     *
     * <p>
     * 两个典型的绝对值场景是调试屏显示的坐标与模拟层圆盘的中心：那两处要的是真值本身，不是与另一个位置作差。
     */
    long farlands$cellCoord(int axis);

    /**
     * 该轴的 256 位值。
     *
     * <p>
     * 槽位未写过时按位置字段的 double 建一个，调用方不必判空。特殊值原样返回，NaN 与无穷的十进制写法由
     * {@code Binary256} 自己给，调用方不必单独甄别。
     */
    Binary256 farlands$exactValue(int axis);

    /**
     * 只写九个槽位，不动投影、包围盒与缓存。
     *
     * <p>
     * 给网络侧的精确位置用：vanilla 的包已经把位置、包围盒、方块与区块缓存、旧位置都落好，这里只把 256 位真值
     * 补回去。旧位置一组值写两处，与 {@code setOldPos} 同形，所以 {@code xo} 与 {@code xOld} 不会分叉。
     */
    void farlands$adoptExact(Binary256 x, Binary256 y, Binary256 z,
            Binary256 oldX, Binary256 oldY, Binary256 oldZ);

    /**
     * 只写位置三槽，不动旧位置、不动 double 投影与缓存。
     *
     * <p>
     * 给客户端上报的精确位置用：服务端的位置与物理仍由原版移动包驱动，这里只把真值补进槽位，供
     * {@code cellCoord} 与段枚举取用。旧位置属于服务端自己，这条不碰。
     */
    void farlands$applyExactPosition(Binary256 x, Binary256 y, Binary256 z);

    /** 标记该实体这一 tick 有精确位置待同步。 */
    void farlands$markExactSync();

    /** 取走并清除「有精确位置待同步」的标记。 */
    boolean farlands$takeExactSync();

    /**
     * 自上一 tick 以来，该轴的方块格号是否变过。
     *
     * <p>
     * 判据是两端各自的精确下取整是否相等，与位移大小无关。不能用 {@link #farlands$frameDelta} 顶替：
     * 位移非零不等于跨格。也不能用 {@link #farlands$anchor}：锚点在 1e12 以下恒为 0，比不出格子。
     */
    boolean farlands$crossedCell(int axis);
}
