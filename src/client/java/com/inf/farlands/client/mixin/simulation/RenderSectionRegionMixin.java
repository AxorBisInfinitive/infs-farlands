package com.inf.farlands.client.mixin.simulation;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 纯客户端地在世界固定坐标渲染一个草方块。
 *
 * <p>
 * 挂在这个取数口，而不是把方块写进客户端 chunk 数据：编译期对段内每一格都会问一次
 * {@code getBlockState}，答案直接进网格，chunk 数据、光照、碰撞与网络一概不碰。
 *
 * <p>
 * 代价是它只活在网格里：不挡光、不可碰撞、不可破坏、服务端不知道。网格每次重编都会再答一次，所以段
 * 重发、相机移动、重进世界都不会让它错位。
 *
 * <p>
 * 坐标取 {@code (0, 128, 0)}，段号 {@code 128 >> 4 = 8}。该段必须已被摘出空段集合，否则它不进八叉树，
 * 也不会被编译，见 {@code ClientChunkCacheMixin}。
 */
@Mixin(RenderSectionRegion.class)
public class RenderSectionRegionMixin {

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    private void farlands$fixedBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (pos.getX() == 0 && pos.getY() == 128 && pos.getZ() == 0) {
            cir.setReturnValue(Blocks.GRASS_BLOCK.defaultBlockState());
        }
    }
}
