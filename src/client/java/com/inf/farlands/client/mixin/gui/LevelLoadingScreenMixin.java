package com.inf.farlands.client.mixin.gui;

import com.inf.farlands.terrain.debug.LoadStageSnapshot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 加载页中间那张网格的读数。
 *
 * <p>原版网格的两头在我们这段加载里都是空的：视图的 moveTo 只由 updateFocus 触发，而第一次 updateFocus 排在
 * 创建期预加载之后，所以泵跑的十几秒里 chunkMap 为 null，每格都取到 null，画出来全黑；就算有中心，它问的也
 * 是原版的 ChunkStatus，看不见我们按段推进的档位。
 *
 * <p>这里只换数据来源：每格报哪个状态由段档位快照决定，绘制、格宽、居中与色表全部沿用原版。快照缺席、当前
 * 屏幕不是加载页时，全部退回原调用，页面与原版逐像素一致。
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

    /**
     * 网格取色：把原版那一次状态查询换成快照里的档位。
     *
     * <p>同一个函数也被 F3 的整块可视化复用，所以先按当前屏幕判一次，那个调试项保持原行为。
     */
    @Redirect(method = "extractChunksForRendering", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/progress/ChunkLoadStatusView;get(II)Lnet/minecraft/world/level/chunk/status/ChunkStatus;"))
    private static ChunkStatus farlands$stageCell(ChunkLoadStatusView view, int x, int z) {
        if (!farlands$onLoadingScreen()) {
            return view.get(x, z);
        }
        LoadStageSnapshot.Snapshot snapshot = LoadStageSnapshot.current();
        if (snapshot == null) {
            return view.get(x, z);
        }
        return farlands$statusOf(snapshot.cell(x, z));
    }

    /**
     * 网格直径：与快照同源，不再依赖「页面算出的半径恰好等于我们的票据半径」这个巧合。
     *
     * <p>两处调用分属两个方法，所以各写一条注入器；多目标会合计命中数，漏一处不报。
     */
    @Redirect(method = "extractChunksForRendering", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/progress/ChunkLoadStatusView;radius()I"))
    private static int farlands$gridDiameter(ChunkLoadStatusView view) {
        return farlands$radiusOr(view);
    }

    /** 文字位置按半径上移，所以这一处也要与同一份数值对齐。 */
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/progress/ChunkLoadStatusView;radius()I"))
    private int farlands$screenGridDiameter(ChunkLoadStatusView view) {
        return farlands$radiusOr(view);
    }

    /** 快照在就用它的半径，否则用页面自己的。 */
    @Unique
    private static int farlands$radiusOr(ChunkLoadStatusView view) {
        if (!farlands$onLoadingScreen()) {
            return view.radius();
        }
        LoadStageSnapshot.Snapshot snapshot = LoadStageSnapshot.current();
        return snapshot != null ? snapshot.radius() : view.radius();
    }

    /** 当前屏幕是不是加载页。F3 的整块可视化复用同一个绘制函数，靠这条把它排除在外。 */
    @Unique
    private static boolean farlands$onLoadingScreen() {
        return Minecraft.getInstance().screen instanceof LevelLoadingScreen;
    }

    /**
     * 档位到原版档位。这一映射就是配色：借原版的色表，就要按原版那十二档的顺序落位。
     *
     * <p>返回 null 表示这一格没有壳，原版对 null 取默认色，即黑色。
     */
    @Unique
    private static ChunkStatus farlands$statusOf(int level) {
        return switch (level) {
            case 0 -> ChunkStatus.EMPTY;
            case 1 -> ChunkStatus.BIOMES;
            case 2 -> ChunkStatus.NOISE;
            case 3 -> ChunkStatus.SURFACE;
            case 4 -> ChunkStatus.CARVERS;
            case 5 -> ChunkStatus.FEATURES;
            case 6 -> ChunkStatus.SPAWN;
            case 7 -> ChunkStatus.FULL;
            default -> null;
        };
    }
}
