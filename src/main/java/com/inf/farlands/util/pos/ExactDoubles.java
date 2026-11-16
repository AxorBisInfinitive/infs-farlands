package com.inf.farlands.util.pos;

import com.inf.long2.binaries.binary1280.Binary1280;

/**
 * 旧位置六轴的 1280 位读写，供各实体类的 FIELD 注入共用同一形状。
 *
 * <p>
 * 读槽位未写过时回落传入的字段值：旧位置在实体构造期就会被写，而位置槽位可能还没建，两者都要能读。
 */
public final class ExactDoubles {

    private ExactDoubles() {
    }

    /** 读槽位。{@code fallback} 是同一字段的当前 double，槽位为空时用它。 */
    public static double read(ExactPosition holder, int slot, double fallback) {
        Binary1280 exact = holder.farlands$exactSlots()[slot];
        return exact != null ? exact.toDouble() : fallback;
    }

    /** 写槽位。对应的 double 字段由调用方负责回写，两者保持逐位相同。 */
    public static void write(ExactPosition holder, int slot, double value) {
        holder.farlands$exactSlots()[slot] = Binary1280.of(value);
    }
}
