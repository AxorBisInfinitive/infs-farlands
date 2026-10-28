package com.inf.farlands.mixin.fix.xyz;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.TeleportCommand;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 传送命令的落点判界改回 double。
 *
 * <p>
 * {@code TeleportCommand.performTeleport} 先用 {@code BlockPos.containing(x, y, z)} 把目标坐标落成
 * {@code BlockPos}，再拿它判 {@code Level.isInSpawnableBounds}。{@code BlockPos} 是三个 int，
 * 超出 int 的坐标在那里饱和到 2147483647，而判界的上限恰好是 2147483647，于是整段界外坐标被一句
 * 「不在可出生范围」拒掉。解析层本身接受 double，卡的就是这一句。
 *
 * <p>
 * 那个 {@code BlockPos} 在本方法里只有一个消费者，就是紧随其后的这次判界，不参与算坐标也不参与发包，
 * 所以这里只换它的值：三轴都在可玩方块界内则原样返回，界外返回 {@code BlockPos.ZERO}，让判界必然通过。
 * 界内的行为因此与不装本模组逐字相同，界外不做这一道校验。
 *
 * <p>
 * 真正的落点不经过 {@code BlockPos}：{@code Entity.teleportTo} 到 {@code ServerPlayer.teleport}
 * 到 {@code Entity.teleportSetPosition} 一路是 double，最后进 {@code setPosRaw}。
 */
@Mixin(TeleportCommand.class)
public class TeleportCommandMixin {

    @Redirect(method = "performTeleport", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos farlands$spawnablePos(double x, double y, double z) {
        if (!WorldBounds.inBlock((long) Math.floor(x))
                || !WorldBounds.inBlock((long) Math.floor(y))
                || !WorldBounds.inBlock((long) Math.floor(z))) {
            return BlockPos.ZERO;
        }
        return BlockPos.containing(x, y, z);
    }
}
