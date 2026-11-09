package com.inf.farlands.client.mixin.render;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 渲染段与数据的对应：只有本列在客户端已收到 chunk、且本段已收到段数据时，才允许进遮挡图。
 *
 * <p>
 * 这道门是 vanilla 的 {@code hasAllNeighbors}，26.1.2 的三个消费点都在可见性链上：
 * {@code LevelRenderer.compileSections} 对 UNCOMPILED 脏段的编译前判，{@code SectionOcclusionGraph} 的入队，以及
 * 邻居事件到达时对等待段的重新入队。
 *
 * <p>
 * 判据取「本列有 chunk 且本段已物化」，不取 vanilla 的「周围 8 个邻 chunk 都在」。正交邻列与本段能不能渲染无关，
 * 而 chunk 按 XZ 成簇到达，判 8 邻会让视距边缘那一圈在邻居到齐前一直不进图，表现是玩家周围一圈不渲染。
 *
 * <p>
 * 反过来，缺列与缺段都必须挡住。界外没有玩家窗口，服务端一个段都不发，客户端只有个别列持有 chunk，而渲染网格按相机
 * 段铺满整个 XZ 视距；不挡的话这些没有数据的列会全部进图并计入可见集，还会派发一批注定为空的编译任务。段缺席同理：
 * 段数据晚于 chunk 到达，此时读到的只是工厂默认空气。
 *
 * <p>
 * 返回假不是丢弃：该段会写进 {@code chunksWaitingForNeighbors}，chunk 或段到达后由
 * {@code onChunkReadyToRender} 的邻居事件把它重新入队。关卡未装时按真处理，与不设这道门时一致。
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class SectionRenderDispatcher$RenderSectionMixin {

    /** 外实例。目标是包私有 final 字段，影子用 private 声明在本仓库已有先例。 */
    @Shadow
    @Final
    private SectionRenderDispatcher this$0;

    /** 本段的 section 键，用来定位所属列与本段。 */
    @Shadow
    public abstract long getSectionNode();

    @Overwrite
    public boolean hasAllNeighbors() {
        ClientLevel level = ((SectionRenderDispatcherAccessor) this.this$0).farlands$level();
        if (level == null) {
            return true;
        }
        SectionPos pos = SectionPos.of(this.getSectionNode());
        if (level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) instanceof LevelChunk chunk) {
            return ((WindowedChunk) chunk).windowedAllSections().containsKey(pos.y());
        }
        return false;
    }
}
