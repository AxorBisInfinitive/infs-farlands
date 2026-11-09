package com.inf.farlands.mixin.position;

import java.util.Optional;

import com.inf.farlands.util.pos.ExactPosition;

import com.inf.long2.binaries.Binary256;

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
 * 实体位置的三轴 256 位值随存档落盘与读回。
 *
 * <p>
 * 原版存的是 {@code "Pos"} 那三个 double，256 位槽位没有出场机会。这条在 {@code saveWithoutId} 的头部另写
 * 一个键 {@code farlands_exact_pos}，用的是 {@code ValueOutput.putIntArray}：一个长拆成高低两个 int，三轴十二个
 * 长装进二十四个 int。读盘在 {@code load} 的返回处，键缺失就什么都不做，旧存档照旧走 double。
 *
 * <p>
 * 写盘有三道跳过的判据。槽位为 null 说明这个实体从没被精确写过；有载具时原版存的是载具位置，实体自己的槽位
 * 不代表落点；槽位与「那个 double 的精确值」逐轴相等说明没有额外精度，写下去纯属冗余。第三条是这条改动能保持
 * 零开销的关键：服务端上的绝大多数实体都命中它。
 *
 * <p>
 * 第三条的比较对象必须是 {@code Binary256.of(position.?)},即那个 double 的精确值，不能是 {@code position} 本身
 * 也不能是 {@code getX()}。{@code position} 字段就是槽位的投影，{@code getX()} 的覆写也返回槽位的投影，两者与
 * 槽位相比都恒等，判据会永远跳过、键永远不写。这一条已经用存档读数核过一次。
 *
 * <p>
 * 写盘注入取头部而不取返回处：{@code saveWithoutId} 里对乘客有循环与提前分支，返回处可能命中多次；头部只命中
 * 一次，且新键与随后原版写的字段不冲突。
 */
@Mixin(Entity.class)
public abstract class EntityExactSaveMixin {

    /** 三轴各四个 long，拆成八个 int，合计二十四个。 */
    @Unique
    private static final int FARLANDS_INTS = 24;

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
        Binary256[] slots = holder.farlands$exactSlots();
        Binary256 x = slots[ExactPosition.POS_X];
        Binary256 y = slots[ExactPosition.POS_Y];
        Binary256 z = slots[ExactPosition.POS_Z];
        if (x == null || y == null || z == null) {
            return;
        }
        // 比的是槽位与那个 double 本身，不是它的投影。position 字段就是槽位的投影，拿两者相比恒等，
        // 判据会永远跳过；vanilla 写进 "Pos" 的正是这个 double，所以只能拿 Binary256.of(double) 与槽位比。
        if (Binary256.of(this.position.x).equals(x) && Binary256.of(this.position.y).equals(y)
                && Binary256.of(this.position.z).equals(z)) {
            return;
        }
        int[] raw = new int[FARLANDS_INTS];
        farlands$put(raw, 0, x);
        farlands$put(raw, 8, y);
        farlands$put(raw, 16, z);
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
        holder.farlands$applyExactPosition(farlands$get(raw, 0), farlands$get(raw, 8), farlands$get(raw, 16));
        // 进场那次 teleport 要把它推给客户端：不发的话客户端拿到的仍是三个 double，槽位又被打回投影，
        // 随后它按 exact_client_position 上报自己的量化值，反把服务端刚读回来的真值改掉。
        holder.farlands$markExactSync();
    }

    /** 一个 256 位值拆成八个 int，高位在前。 */
    @Unique
    private static void farlands$put(int[] raw, int offset, Binary256 value) {
        for (int i = 0; i < 4; i++) {
            long bits = value.getRawLong(i);
            raw[offset + i * 2] = (int) (bits >>> 32);
            raw[offset + i * 2 + 1] = (int) bits;
        }
    }

    /** 八个 int 还原一个 256 位值。 */
    @Unique
    private static Binary256 farlands$get(int[] raw, int offset) {
        long a = ((long) raw[offset] << 32) | (raw[offset + 1] & 0xFFFFFFFFL);
        long b = ((long) raw[offset + 2] << 32) | (raw[offset + 3] & 0xFFFFFFFFL);
        long c = ((long) raw[offset + 4] << 32) | (raw[offset + 5] & 0xFFFFFFFFL);
        long d = ((long) raw[offset + 6] << 32) | (raw[offset + 7] & 0xFFFFFFFFL);
        return Binary256.fromRawBits(a, b, c, d);
    }
}
