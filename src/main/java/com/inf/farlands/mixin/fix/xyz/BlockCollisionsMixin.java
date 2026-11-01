package com.inf.farlands.mixin.fix.xyz;

import net.minecraft.world.level.BlockCollisions;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 碰撞枚举的格跨度在溢出量级上截断。
 *
 * <p>
 * {@code BlockCollisions} 的构造器把盒换算成六个整数格端点，再交给 {@code Cursor3D} 求三元乘积作为迭代总次数。
 * 盒的两端一正一负无穷时，{@code Mth.floor} 分别饱和到 int 两端，减一与加一又各自回绕，跨度算出来是负数，
 * 乘积经 int 回绕成一个二十亿量级的正数，于是每次碰撞查询都要迭代二十亿次，主线程停在那里。
 *
 * <p>
 * 判据放在「原版算不算得对」上，而不是放在有限性上：六个端点的跨度都是正数、每轴不超过 {@link #AXIS_LIMIT}、
 * 乘积不超过 {@link #CELL_LIMIT} 时，原版算术本来是对的，实参原样放行。这个条件覆盖一切实体碰撞盒，实体尺寸是
 * 个位数量级；只有溢出量级的输入才会落到截断分支。
 *
 * <p>
 * 截断取前缀，每轴从 {@code min} 起最多 64 格，乘积不超过 262144。非有限盒里没有「正确的格」，前缀只为让枚举
 * 有限且坐标仍是 int。
 *
 * <p>
 * {@code method} 写全描述符，不写裸 {@code <init>}：本类有两个构造器，另一个只做 {@code this(...)} 转发，
 * 那一支里没有这次调用，按方法逐个核命中时会以 0/1 失败并中断整个应用。
 */
@Mixin(BlockCollisions.class)
public class BlockCollisionsMixin {

    /** 单轴跨度上限，超过即认为原版算术已经溢出。 */
    private static final int AXIS_LIMIT = 1024;

    /** 三元乘积上限，同上。 */
    private static final long CELL_LIMIT = 262144L;

    /** 截断后每轴的实际跨度。 */
    private static final int PREFIX_SPAN = 64;

    @ModifyArgs(method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Cursor3D;<init>(IIIIII)V"))
    private void farlands$capCursorSpan(Args args) {
        // 先按 int 取，再做 long 运算。直接写 (long) args.get(3) 会被编译成 checkcast Long 再拆箱，
        // 而实参的装箱类型是 Integer，运行期抛 ClassCastException。
        int minX = args.get(0);
        int minY = args.get(1);
        int minZ = args.get(2);
        int maxX = args.get(3);
        int maxY = args.get(4);
        int maxZ = args.get(5);
        if (farlands$representable((long) maxX - minX + 1L, (long) maxY - minY + 1L,
                (long) maxZ - minZ + 1L)) {
            return;
        }
        args.set(3, (int) Math.min((long) minX + PREFIX_SPAN - 1L, Integer.MAX_VALUE));
        args.set(4, (int) Math.min((long) minY + PREFIX_SPAN - 1L, Integer.MAX_VALUE));
        args.set(5, (int) Math.min((long) minZ + PREFIX_SPAN - 1L, Integer.MAX_VALUE));
    }

    /**
     * 三轴跨度是否都落在原版算得对的范围内。
     *
     * <p>
     * 先判每轴，再判乘积：每轴不超过 {@code AXIS_LIMIT} 时乘积最多 {@code 2^30}，long 上不会溢出。
     */
    private static boolean farlands$representable(long width, long height, long depth) {
        if (width <= 0L || height <= 0L || depth <= 0L) {
            return false;
        }
        if (width > AXIS_LIMIT || height > AXIS_LIMIT || depth > AXIS_LIMIT) {
            return false;
        }
        return width * height * depth <= CELL_LIMIT;
    }
}
