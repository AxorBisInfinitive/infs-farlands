package com.inf.farlands.mixin.position;

import java.util.Optional;

import com.inf.farlands.util.pos.ExactPosition;

import com.inf.long2.binaries.binary1280.Binary1280;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体位置的三轴 1280 位值随存档落盘与读回。
 *
 * <p>
 * 原版存的是 {@code "Pos"} 那三个 double，1280 位槽位没有出场机会。这条在 {@code saveWithoutId} 的头部另写
 * 一个键 {@code farlands_exact_pos}，用的是 {@code ValueOutput.putIntArray}：一个长拆成高低两个 int，三轴十二个
 * 长装进四十个 int。读盘在 {@code load} 的返回处，键缺失就什么都不做，旧存档照旧走 double。
 *
 * <p>
 * 写盘有三道跳过的判据。槽位为 null 说明这个实体从没被精确写过；有载具时原版存的是载具位置，实体自己的槽位
 * 不代表落点；槽位与「那个 double 的精确值」逐轴相等说明没有额外精度，写下去纯属冗余。第三条是这条改动能保持
 * 零开销的关键：服务端上的绝大多数实体都命中它。
 *
 * <p>
 * 第三条的比较对象必须是 {@code Binary1280.of(position.?)},即那个 double 的精确值，不能是 {@code position} 本身
 * 也不能是 {@code getX()}。{@code position} 字段就是槽位的投影，{@code getX()} 的覆写也返回槽位的投影，两者与
 * 槽位相比都恒等，判据会永远跳过、键永远不写。这一条已经用存档读数核过一次。
 *
 * <p>
 * 写盘注入取头部而不取返回处：{@code saveWithoutId} 里对乘客有循环与提前分支，返回处可能命中多次；头部只命中
 * 一次，且新键与随后原版写的字段不冲突。
 */
@Mixin(Entity.class)
public abstract class EntityExactSaveMixin {

    /** 三轴各二十个 long，拆成四十个 int，合计一百二十个。 */
    @Unique
    private static final int FARLANDS_INTS = 120;

    /** 一条坐标拆成几个 int。二十组 long 每组两个 int。 */
    @Unique
    private static final int FARLANDS_INTS_PER_AXIS = 40;

    @Unique
    private static final String FARLANDS_KEY = "farlands_exact_pos";

    @Shadow
    private Vec3 position;

    @Inject(method = "saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @At("HEAD"))
    private void farlands$saveExactPosition(ValueOutput output, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.getVehicle() != null || !(self instanceof ExactPosition holder)) {
            return;
        }
        Binary1280[] slots = holder.farlands$exactSlots();
        Binary1280 x = slots[ExactPosition.POS_X];
        Binary1280 y = slots[ExactPosition.POS_Y];
        Binary1280 z = slots[ExactPosition.POS_Z];
        if (x == null || y == null || z == null) {
            return;
        }
        // 比的是槽位与那个 double 本身，不是它的投影。position 字段就是槽位的投影，拿两者相比恒等，
        // 判据会永远跳过；vanilla 写进 "Pos" 的正是这个 double，所以只能拿 Binary1280.of(double) 与槽位比。
        if (Binary1280.of(this.position.x).equals(x) && Binary1280.of(this.position.y).equals(y)
                && Binary1280.of(this.position.z).equals(z)) {
            return;
        }
        int[] raw = new int[FARLANDS_INTS];
        farlands$put(raw, 0, x);
        farlands$put(raw, FARLANDS_INTS_PER_AXIS, y);
        farlands$put(raw, FARLANDS_INTS_PER_AXIS * 2, z);
        output.putIntArray(FARLANDS_KEY, raw);
    }

    @Inject(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At("RETURN"))
    private void farlands$loadExactPosition(ValueInput input, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof ExactPosition holder)) {
            return;
        }
        Optional<int[]> read = input.getIntArray(FARLANDS_KEY);
        if (read.isEmpty()) {
            return;
        }
        int[] raw = read.get();
        if (raw.length != FARLANDS_INTS) {
            return;
        }
        holder.farlands$applyExactPosition(farlands$get(raw, 0), farlands$get(raw, FARLANDS_INTS_PER_AXIS),
                farlands$get(raw, FARLANDS_INTS_PER_AXIS * 2));
        // 进场那次 teleport 要把它推给客户端：不发的话客户端拿到的仍是三个 double，槽位又被打回投影，
        // 随后它按 exact_client_position 上报自己的量化值，反把服务端刚读回来的真值改掉。
        holder.farlands$markExactSync();
    }

    /** 一个 1280 位值拆成四十个 int，高位在前。二十个 long 来自 {@code toBytes} 的 160 字节大端编码。 */
    @Unique
    private static void farlands$put(int[] raw, int offset, Binary1280 value) {
        byte[] bytes = value.toBytes();
        for (int i = 0; i < FARLANDS_INTS_PER_AXIS / 2; i++) {
            long bits = 0L;
            for (int b = 0; b < 8; b++) {
                bits = (bits << 8) | (bytes[i * 8 + b] & 0xFFL);
            }
            raw[offset + i * 2] = (int) (bits >>> 32);
            raw[offset + i * 2 + 1] = (int) bits;
        }
    }

    /** 四十个 int 还原一个 1280 位值：摊成 160 字节大端，再交给 {@code of(byte[])}。 */
    @Unique
    private static Binary1280 farlands$get(int[] raw, int offset) {
        byte[] bytes = new byte[FARLANDS_INTS_PER_AXIS / 2 * Long.BYTES];
        for (int i = 0; i < FARLANDS_INTS_PER_AXIS / 2; i++) {
            long bits = ((long) raw[offset + i * 2] << 32) | (raw[offset + i * 2 + 1] & 0xFFFFFFFFL);
            for (int b = 0; b < 8; b++) {
                bytes[i * 8 + b] = (byte) (bits >>> (56 - b * 8));
            }
        }
        return Binary1280.of(bytes);
    }
}
