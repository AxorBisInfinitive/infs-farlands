package com.inf.farlands.mixin.fix.thread;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 非服务端线程上的方块实体标脏不再阻塞取 chunk。
 *
 * <p>原版这一支只有一句实质动作，即给 chunk 打 unsaved 标记：
 * {@code if (hasChunkAt(pos)) getChunkAt(pos).markUnsaved();}。而 {@code getChunkAt} 要的是 FULL，
 * 缓存里只有 ProtoChunk 时它会阻塞等生成。装饰跑在 {@code farlands-gen} 上，一旦有地物在放置后改动
 * 方块实体，例如 {@code MonsterRoomFeature} 给刷怪笼写实体 id，就会走这条阻塞取数：池线程投一个任务
 * 给主线程并等它，主线程替它跑时又阻塞在同一个 future 上，而那个 future 只能靠同一个 chunk 的下一个
 * 作业补，那个作业又被本任务的认领挡着。自己等自己。
 *
 * <p>短路只对服务端且非其运行线程生效：主线程逐字保留原路径，客户端不受影响，比较器那一侧的
 * {@code updateNeighbourForOutputSignal} 也不动，它只在已加载时取数。
 *
 * <p>代价是这一次 unsaved 标记不再落下，由 {@code DecorationFiller.finish} 在装完方块实体之后按所属
 * chunk 补一次。两者的分工写在那里的注释里。
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityChangedMixin {

    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void farlands$skipBlockingMarkUnsaved(BlockPos pos, CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level
                && Thread.currentThread() != level.getServer().getRunningThread()) {
            ci.cancel();
        }
    }
}
