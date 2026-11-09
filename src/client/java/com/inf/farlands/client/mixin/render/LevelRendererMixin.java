package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.compat.sodium.SodiumWindowSync;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每帧把 chunk 窗口拉正到相机段。
 *
 * chunk 的 windowSections 构造默认只有 1 段，windowMinY=-4，不改就装不下内容：SectionCopy 用的
 * 索引是 sectionY - windowMinY，数组长度只有 1，于是索引越界拿到 null，该 chunk 编译成空气。
 * 窗口必须每帧拉，因为 chunk 会在相机不动时陆续加载进来，而 vanilla 只在相机跨 section 时才会
 * 扫一遍，之后加载的 chunk 永远没被扫到。
 *
 * 不调 vanilla 的 repositionCamera：它结尾会
 * getSectionOcclusionGraph().invalidate()，每帧调等于
 * 每帧整图重建，还附赠每帧一遍全部渲染槽位的比较与重指。渲染网格的竖直环绕改由 vanilla 自己的
 * 跨段守卫驱动，那个守卫比较相机 section 的 X/Y/Z 三者，且 cullTerrain 在 compileSections 之前，
 * 时序够用。
 *
 * 只枚举已加载 chunk：未加载的没有数据可显示，等它加载时构造路径会自己建窗口。buildWindow 在
 * 窗口未变时早退，因此每帧调用在相机不跨 section 时零开销。
 *
 * <p>
 * 同一个钩子还驱动 Sodium 侧的 section 集合同步：Sodium 的集合由 XZ 驱动，竖直滑动不产生事件，必须在
 * 窗口拉正之后按 chunk 比对并通知它。门控与实现在 client/compat/sodium 里，未装 Sodium 时零开销。
 *
 * <p>
 * 半径取自客户端选项而不是 ViewArea：Sodium 在 allChanged 上把喂给 ViewArea 构造的那次渲染距离重定向
 * 为 0，它的注释是不允许分配任何资源，ViewArea.getViewDistance() 因此在 Sodium 下恒为 0，拿它当半径会
 * 把枚举退化成 1x1，只有相机所在的 chunk 被拉正。该选项是 vanilla 与 Sodium 共用的那个值，旧版 Sodium
 * 路径也取自它。
 *
 * <p>
 * 窗口滑动之后还要把差量同步进空段集合。那个集合是遮挡图判断「一段能不能进图」的唯一依据，而它的进账只在
 * chunk 加入与段包到达两处发生，窗口滑动不在其中。不同步的后果是：滑进来的段即使全是空气也不在集合里，遮挡图把
 * 它当成有内容的段收进八叉树，随后的视锥访问把它计入可见集，表现为大量看不见却参与编译与遍历的段。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Shadow
    private ViewArea viewArea;

    @Shadow
    private ClientLevel level;

    @Inject(method = "update", at = @At("HEAD"))
    private void farlands$followCameraWindow(Camera camera, CallbackInfo ci) {
        if (camera == null || this.viewArea == null || this.level == null) {
            return;
        }
        SectionPos camSection = SectionPos.of(camera.blockPosition());
        int camSecY = camSection.y();
        int radius = Minecraft.getInstance().options.getEffectiveRenderDistance();
        ChunkPos cpos = camSection.chunk();
        ClientChunkCache chunkCache = this.level.getChunkSource();
        boolean sodium = SodiumWindowSync.beginFrame(this.level);
        for (int cx = cpos.x() - radius; cx <= cpos.x() + radius; cx++) {
            for (int cz = cpos.z() - radius; cz <= cpos.z() + radius; cz++) {
                LevelChunk chunk = (LevelChunk) this.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk != null && !(chunk instanceof EmptyLevelChunk)) {
                    WindowedChunk windowed = (WindowedChunk) chunk;
                    int oldMin = windowed.getWindowMinY();
                    int oldMax = windowed.getWindowMaxY();
                    windowed.moveWindowTo(camSecY);
                    farlands$syncEmptySections(chunkCache, chunk, oldMin, oldMax);
                    if (sodium) {
                        SodiumWindowSync.onChunk(chunk);
                    }
                }
            }
        }
        if (sodium) {
            SodiumWindowSync.endFrame();
        }
    }

    /**
     * 把一次窗口滑动的差量同步进空段集合：滑进来的按 {@code hasOnlyAir} 登记，滑出去的撤销登记。
     *
     * <p>
     * 只做差量，不整窗重扫：整窗是 35 乘以列数，竖直飞行时每 tick 都要重扫一遍，代价大于它修掉的问题。滑进来的段由
     * {@code getSection} 现取，窗口滑动已经把它们物化过，这一步不新造对象。
     *
     * <p>
     * 滑出用 {@code empty = false}：它同时摘掉可能残留的旧键，并连带 {@code onSectionBecomingNonEmpty}。本方法在窗口
     * 移动之后调用，那一层已经不在网格带内，该连带因此取不到渲染段，是空操作。这个先后顺序不能反。
     */
    private static void farlands$syncEmptySections(ClientChunkCache cache, LevelChunk chunk, int oldMin, int oldMax) {
        WindowedChunk windowed = (WindowedChunk) chunk;
        int newMin = windowed.getWindowMinY();
        int newMax = windowed.getWindowMaxY();
        if (newMin == oldMin && newMax == oldMax) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        for (int sy = newMin; sy <= newMax; sy++) {
            if (sy >= oldMin && sy <= oldMax) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
            if (section == null) {
                continue;
            }
            cache.onSectionEmptinessChanged(pos.x(), sy, pos.z(), section.hasOnlyAir());
        }
        for (int sy = oldMin; sy <= oldMax; sy++) {
            if (sy >= newMin && sy <= newMax) {
                continue;
            }
            cache.onSectionEmptinessChanged(pos.x(), sy, pos.z(), false);
        }
    }
}
