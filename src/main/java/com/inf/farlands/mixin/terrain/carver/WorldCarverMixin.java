package com.inf.farlands.mixin.terrain.carver;

import com.inf.farlands.terrain.carverFiller.CarverSectionWrite;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 雕刻阶段的写方块改为直写 section，绕开 ChunkAccess.setBlockState 的副作用。
 *
 * 与 surface 同一个病：carve 也跑在 genPool 线程上，而 WorldCarver.carveBlock 最终落到
 * ChunkAccess.setBlockState，后者会走 onPlace -> scheduleTick。于是 genPool 线程去写
 * LevelChunkTicks 的 PriorityQueue，与服务端线程 LevelTicks.tick 的 poll 交错，破坏这个
 * 非线程安全的堆。实测栈为 LiquidBlock.onPlace -> ScheduledTickAccess.scheduleTick。
 *
 * 两处写入都重定向：carveBlock 本体一处，topMaterial 的静态 lambda 一处。取值与索引语义保持
 * 原版：同一 section、同一局部坐标、同一 state，只是无锁直写。副作用 onPlace、scheduleTick、
 * markAndNotifyBlock、neighborChanged 一并消失，与 fill 路径一致。
 *
 * <p>下界的 NETHER_CAVE 是 NetherWorldCarver 对 carveBlock 的覆写，上面这两条覆盖不到它，那一处的
 * 写入由 NetherWorldCarverMixin 处理。
 *
 * 调用方对返回值不感兴趣，原字节码在调用后立即 pop，因此返回 null 安全。
 */
@Mixin(net.minecraft.world.level.levelgen.carver.WorldCarver.class)
public class WorldCarverMixin {

    @Redirect(method = "carveBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState farlands$directSectionWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        CarverSectionWrite.write(chunk, pos, state);
        return null;
    }

    /**
     * carveBlock 里 topMaterial 那次写入编译进静态 lambda，上面那条覆盖不到它。
     * 目标方法是 static，handler 必须同为 static。
     */
    @Redirect(method = "lambda$carveBlock$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState farlands$directSectionWriteInLambda(ChunkAccess chunk, BlockPos pos, BlockState state) {
        CarverSectionWrite.write(chunk, pos, state);
        return null;
    }
}
