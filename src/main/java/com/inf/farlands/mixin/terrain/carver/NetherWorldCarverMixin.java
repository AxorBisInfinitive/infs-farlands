package com.inf.farlands.mixin.terrain.carver;

import com.inf.farlands.terrain.carverFiller.CarverSectionWrite;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.carver.NetherWorldCarver;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 下界雕刻的写方块改为直写 section。
 *
 * <p>{@code NetherWorldCarver} 覆写了 {@code carveBlock}，基类那条 redirect 覆盖不到它：y 不高于
 * {@code minGenY + 31} 时它经 {@code ChunkAccess.setBlockState} 写岩浆，落到
 * {@code LiquidBlock.shouldSpreadLiquid}，再由 {@code level.getFluidState} 对邻居 chunk 阻塞取数。
 * carve 跑在 farlands-gen 上，那次阻塞等的是要靠同一个池推进的 FULL，池被占满即双向等死。
 *
 * <p>{@code method} 必须写全描述符：该类有两个同名的 {@code carveBlock}，另一个是编译器生成的 bridge，
 * 它体内没有这个写入点，写裸名会让命中计数被它凑够。
 */
@Mixin(NetherWorldCarver.class)
public class NetherWorldCarverMixin {

    @Redirect(method = "carveBlock(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;"
            + "Lnet/minecraft/world/level/levelgen/carver/CaveCarverConfiguration;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/function/Function;"
            + "Lnet/minecraft/world/level/chunk/CarvingMask;"
            + "Lnet/minecraft/core/BlockPos$MutableBlockPos;"
            + "Lnet/minecraft/core/BlockPos$MutableBlockPos;"
            + "Lnet/minecraft/world/level/levelgen/Aquifer;"
            + "Lorg/apache/commons/lang3/mutable/MutableBoolean;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState("
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState farlands$directSectionWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        CarverSectionWrite.write(chunk, pos, state);
        return null;
    }
}
