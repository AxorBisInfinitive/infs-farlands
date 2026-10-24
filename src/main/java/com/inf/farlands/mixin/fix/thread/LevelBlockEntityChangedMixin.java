package com.inf.farlands.mixin.fix.thread;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 非服务端线程上的方块实体标脏不再阻塞取 chunk。
 *
 * <p>原版 {@code BlockEntity.setChanged(Level, BlockPos, BlockState)} 在这一层做两件事：先
 * {@code level.blockEntityChanged}，其中只有一句实质动作，即给 chunk 打 unsaved 标记，代价是一次
 * {@code getChunkAt}；再 {@code level.updateNeighbourForOutputSignal}，它对水平四邻里在场的方向各取一次
 * 方块态，代价同样是一次 {@code getChunk}。两条取数都要 {@code ChunkStatus.FULL}，缓存里只有壳时会阻塞
 * 等生成。
 *
 * <p>两句在装饰期都会被走到。装饰跑在 {@code farlands-gen} 上，一旦有地物在放置后改动方块实体，例如
 * {@code MonsterRoomFeature} 给刷怪笼写实体 id，池线程就会投一个任务给主线程并等它，而主线程替它跑时又
 * 阻塞在同一个 future 上，那个 future 只能靠 tick 里的调度器补，主线程却正卡在 tick 之外的等待里。自己
 * 等自己。
 *
 * <p>短路只对服务端且非其运行线程生效：主线程逐字保留原路径，客户端不受影响。
 *
 * <p>代价分两条。{@code blockEntityChanged} 少一次 unsaved 标记，由 {@code DecorationFiller.finish} 在
 * 装完方块实体之后按所属 chunk 补一次。{@code updateNeighbourForOutputSignal} 少一次相邻比较器刷新，
 * 这一条不补：比较器要成立需要有玩家放下的比较器与它旁边的方块态，而装饰期这一笔落在正在生成的那一片，
 * 那种摆放不存在。
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityChangedMixin {

    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void farlands$skipBlockingMarkUnsaved(BlockPos pos, CallbackInfo ci) {
        if (farlands$isOffServerThread()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateNeighbourForOutputSignal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"), cancellable = true)
    private void farlands$skipBlockingNeighbourSignal(BlockPos pos, Block changedBlock, CallbackInfo ci) {
        if (farlands$isOffServerThread()) {
            ci.cancel();
        }
    }

    /** 服务端上，且当前线程不是该服务端的运行线程。 */
    private boolean farlands$isOffServerThread() {
        return (Object) this instanceof ServerLevel level
                && Thread.currentThread() != level.getServer().getRunningThread();
    }
}
