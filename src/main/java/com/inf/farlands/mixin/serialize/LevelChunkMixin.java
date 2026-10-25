package com.inf.farlands.mixin.serialize;

import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.PendingFluidPostProcess;
import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.Map;

import it.unimi.dsi.fastutil.shorts.ShortList;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 写方块的段级入口，与待建方块实体的消费门。
 *
 * <p>写入侧：普通方块写最终都落到 setBlockState 里那一次 LevelChunkSection.setBlockState
 * 调用，它走带线程检测的调色板重载，而 fsa 编码在 farlands-encode 池上打包同一个容器，
 * 两者跨线程相遇即 PalettedContainer 抛异常。所以这一次调用要在 SectionSerializer.packLockFor
 * 内做，键与另外五个打包点相同。随后的四张高度图更新同样在这把锁内，因为装饰在池上直写段时也更新
 * 同一批高度图。锁不含本方法后面的光照。
 *
 * <p>标脏侧：只在修改实际发生时标脏，判据是返回值非 null，vanilla 在 blockstate 等于 state
 * 的分支返回 null 表示没变。双端共享类，客户端标脏无害，SectionLifecycle 是纯服务端，
 * 客户端从不查询。
 *
 * <p>26.1.2 的签名是 setBlockState(BlockPos, BlockState, int flags)，已 javap 核实。另一处标脏点是
 * terrain 的 fill，GenTask 逐段 fill 与 surface、carvers 升段后标脏。
 *
 * <p>方块实体侧：pending 标签的消费与清表都按段判定。段没落位的标签不得摘走、也不得被清掉，因为那一刻
 * 没有方块态可以建实例，而方块在 fsa 里、要等读回才到。两处判据见本类尾部两个 redirect。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    /**
     * 调色板写入纳入打包锁。
     *
     * <p>形参表是重定向调用的接收者加实参，接收者是 LevelChunkSection；本类的方法是实例方法，
     * 因此 this 就是 LevelChunk，锁键从它取。
     */
    @Redirect(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState farlands$writeUnderPackLock(LevelChunkSection section, int localX, int localY, int localZ,
            BlockState state) {
        synchronized (SectionSerializer.packLockFor(((LevelChunk) (Object) this).getPos().pack())) {
            return section.setBlockState(localX, localY, localZ, state);
        }
    }

    /**
     * 四张最终高度图的更新纳入同一把包锁。
     *
     * <p>装饰在 farlands-gen 上直写段，并在 {@code DecorationRegion.setBlock} 里更新同一批高度图，而高度图
     * 是 BitStorage 加一张普通 map。装饰与玩家并发写现在允许了，两侧的高度图更新就必须在同一把锁内，否则
     * 两个线程会写同一个 BitStorage。本方法内四次 update 是同一个 target，不带 ordinal 一次全覆盖。
     */
    @Redirect(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Heightmap;update(IIILnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean farlands$heightmapsUnderPackLock(Heightmap heightmap, int localX, int localY, int localZ,
            BlockState state) {
        synchronized (SectionSerializer.packLockFor(((LevelChunk) (Object) this).getPos().pack())) {
            return heightmap.update(localX, localY, localZ, state);
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void farlands$markSectionDirty(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            ((WindowedChunk) this).markSectionDirty(pos.getY() >> 4);
        }
    }

    /**
     * 段未就位时不消费 pending 标签。
     *
     * <p>
     * vanilla 的原序是先 {@code remove} 再 {@code promotePendingBlockEntity}，而晋升要拿方块态去建实例：
     * 段还没读回来时读到的是空气，{@code BlockEntity.loadStatic} 判定非法、吞掉异常、返回 null，标签随之
     * 消失。本 port 的方块在 fsa，载入瞬间必然没有方块，所以这道守卫是必须的：段未就绪就返回 null、不摘
     * 标签，等该段被读回灌好之后由 {@code SectionLifecycle} 的读回收尾那一处晋升。
     *
     * <p>
     * 「段已定」与写入门同源，用 {@link ChunkReadiness}：已到 LIGHTED 且此刻不在读回。
     *
     * <p>
     * 本方法里有两处 {@code Map.remove}。本处作用在 {@code pendingBlockEntities} 上，另一处在后面的
     * {@code isRemoved} 分支上，作用在活表 {@code blockEntities} 上；裸写会同时命中两处，因此钉
     * {@code ordinal = 0}。客户端 chunk 的 pending 表恒空，这里直接走原路。
     */
    @Redirect(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
    private Object farlands$keepPendingUntilReady(Map<BlockPos, CompoundTag> pending, Object key) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level) || !pending.containsKey(key)) {
            return pending.remove(key);
        }
        BlockPos pos = (BlockPos) key;
        if (!ChunkReadiness.isReady(level, self.getPos(), pos.getY() >> 4)) {
            return null;
        }
        return pending.remove(key);
    }

    /**
     * ticking 准备那一轮晋升走完之后不清空 pending 表。
     *
     * <p>
     * {@code postProcessGeneration} 结尾会 clear。此时表里剩下的条目恰好是上一处守卫拒掉的，也就是所在段
     * 还没落位的标签；清掉等于把守卫白做，它们会在这一轮无声消失。留给读回收尾那一处晋升。
     */
    @Redirect(method = "postProcessGeneration(Lnet/minecraft/server/level/ServerLevel;)V", at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V"))
    private void farlands$keepUnreadyPending(Map<BlockPos, CompoundTag> pending) {
    }

    /**
     * 生成期流体的结算。
     *
     * <p>
     * 下面那个原版循环只读 postProcessing 表，而本 port 的 chunk 从存在流程起就是 LevelChunk，生成期的
     * 记账落在 ChunkAccess 的覆写里，这张数组在生成路径上恒为空，于是循环一项都跑不到：含水层与地表规则
     * 写下的流体写完就再也不会被 tick，边界的水因此不向相邻空腔铺开。
     *
     * <p>
     * 挂 HEAD 而不是 TAIL：原版顺序就是流体循环先于待建方块实体的晋升，HEAD 与它同位。此刻本 chunk 与它
     * 3x3 邻居的 FULL future 都已完成，流体规则的邻居取数不会进阻塞分支。
     *
     * <p>
     * 写入仍过 Level 的门：已移出窗口的段、未到终态的段、读回在途的段都会被拒，那几格保持生成时的原样。
     */
    @Inject(method = "postProcessGeneration(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"))
    private void farlands$settleGeneratedFluid(ServerLevel level, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        Map<Integer, ShortList> pending = ((PendingFluidPostProcess) this).takePendingFluidPost();
        if (pending.isEmpty()) {
            return;
        }
        ChunkPos chunkPos = self.getPos();
        for (Map.Entry<Integer, ShortList> entry : pending.entrySet()) {
            int sectionY = entry.getKey();
            for (short packed : entry.getValue()) {
                BlockPos pos = ProtoChunk.unpackOffsetCoordinates(packed, sectionY, chunkPos);
                BlockState state = self.getBlockState(pos);
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    fluid.tick(level, pos, state);
                }

                if (state.getBlock() instanceof LiquidBlock) {
                    state.tick(level, pos, level.getRandom());
                } else {
                    BlockState next = Block.updateFromNeighbourShapes(state, level, pos);
                    if (next != state) {
                        level.setBlock(pos, next, 276);
                    }
                }
            }
        }
    }
}
