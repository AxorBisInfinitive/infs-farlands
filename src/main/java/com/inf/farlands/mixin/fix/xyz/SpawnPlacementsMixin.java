package com.inf.farlands.mixin.fix.xyz;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.LevelReader;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 生成位置落在可玩方块界之外时直接判否，不往下读方块。
 *
 * <p>
 * 生成点是对玩家位置做 int 加法得到的，横向贴边时这类加法会绕到对侧极端：CatSpawner 取
 * {@code blockPosition().offset(正负 8 到 31)}，x 接近 2^31 时结果为负，落点跑到可玩界之外。那一步之后
 * 的取数是加载版，{@code Level.getBlockState} 经 {@code Level.getChunk} 的 {@code requireChunk=true}
 * 阻塞等一个永远不会就绪的 chunk：界外 chunk 不进调度器，没人把它补成 FULL，服务端线程因此卡在世界
 * tick 里，表现是整局冻结。
 *
 * <p>
 * 判据落在「这个位置能不能生成」本身。界外的位置没有可表示的方块坐标，返回假是它唯一合理的取值；界内
 * 逐字不变，因为守卫只多一次范围比较。
 *
 * <p>
 * 放在这个入口而不是逐个 spawner：它是各族共用的那一个，四个调用点一次覆盖。Raid 与
 * WanderingTraderSpawner 直接对实现类调接口方法，不经本入口，不在覆盖范围内。
 */
@Mixin(SpawnPlacements.class)
public class SpawnPlacementsMixin {

    @Inject(method = "isSpawnPositionOk(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private static void farlands$rejectOutsidePlayable(EntityType<?> type, LevelReader level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!WorldBounds.inBlockXZ(pos.getX(), pos.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
