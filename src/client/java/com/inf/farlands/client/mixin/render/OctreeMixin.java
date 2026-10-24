package com.inf.farlands.client.mixin.render;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.client.renderer.Octree;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * octree 根盒的三轴范围都改成以相机 section 中心为中。
 *
 * vanilla 只在水平方向拿相机 section 原点当基准，竖直方向在 boundingBoxSizeInSections >=
 * sectionsPerChunk 时锚到 level.getMinY()，那是维度下界 -64。渲染网格竖直环绕之后槽位会跑到
 * -64 以下，盒外的 section 会落进一个不包含它的叶子盒，visitNodes 的视锥裁剪按错盒判，出现
 * 随视角变化的误裁。
 *
 * 竖直跨度不能沿用 boundingBoxSizeInSections：它由水平视距推出，rd=8 时只有 512 格即相机上下 16 段，
 * 小于网格的 ±17。这里按网格段数向上取到 2 的幂，与水平视距解耦。
 *
 * <p>X/Z 一并改成以相机 section 原点为中。vanilla 那两轴用 {@code origin - distance} 起算，相机只是
 * 恰好落在盒内，并没有被放在中心；而相机段原点可到 ±2.14B 附近，加半个跨度就越过 int 边界，在 int 里
 * 相加会回绕成负值，盒从构造那一刻就是反序的。
 *
 * <p><b>锚点必须是段原点，不能是段中心。</b>{@code SectionPos.center()} 是 {@code origin + 8}，而每级
 * 切分面等于 {@code min + k * span/2}，{@code span/2} 是 16 的倍数，所以切分面的模 16 余数就是
 * {@code min} 的余数。锚在段中心时余数是 8，切分面整片切进段内部，分支盒于是不含它装着的段，而
 * {@code visitNodes} 拿分支盒做视锥判定，能裁掉仍在视锥里的段。
 *
 * <p><b>盒宽必须恒等于 {@code 2 * halfSpanBlocks}，不能在端点处被压窄。</b>
 * {@code Octree$Branch.areChildrenLeaves} 的判据是 {@code getXSpan() == 32}，而下降每层只留一半，
 * 宽度按 {@code 1024, 512, 256, 128, 64, 32} 走，恰好踩到 32 才有出口。把越界那一端单独
 * 夹到 int 边界会把宽度改成 536、552、520、504 这类值，序列变成
 * {@code 536, 268, 134, 67, 33, 16}，<b>在 32 旁边跳过</b>，递归永不终止，
 * 异步全量重建抛 {@code StackOverflowError}，遮挡图建不出来，可见集为空，而编译只遍历可见集，
 * 于是所有 section 都停在 dirty 与 UNCOMPILED。
 *
 * <p>因此这里做的是整盒平移：先把段原点夹进"整盒仍落在 int 内"的可行窗口，再按 16 下取整，最后由这个
 * 原点取两端。两端都由同一个原点决定，六个 {@code @ModifyArg} 各自重算同一个盒，互不依赖，无状态。
 * 正常坐标下窗口不生效，与平移前逐位相同。
 */
@Mixin(Octree.class)
public class OctreeMixin {

    @Shadow
    private BlockPos cameraSectionCenter;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 1)
    private int farlands$cameraCenteredMinY(int minY) {
        return farlands$boxMin(this.cameraSectionCenter.getY());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 4)
    private int farlands$cameraCenteredMaxY(int maxY) {
        return farlands$boxMax(this.cameraSectionCenter.getY());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 0)
    private int farlands$cameraCenteredMinX(int minX) {
        return farlands$boxMin(this.cameraSectionCenter.getX());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 3)
    private int farlands$cameraCenteredMaxX(int maxX) {
        return farlands$boxMax(this.cameraSectionCenter.getX());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 2)
    private int farlands$cameraCenteredMinZ(int minZ) {
        return farlands$boxMin(this.cameraSectionCenter.getZ());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 5)
    private int farlands$cameraCenteredMaxZ(int maxZ) {
        return farlands$boxMax(this.cameraSectionCenter.getZ());
    }

    /** 网格竖直段数向上取到 2 的幂再折半，单位方块。取整到 2 的幂保证盒边与段边界对齐。 */
    @Unique
    private static int farlands$halfSpanBlocks() {
        int gridSections = FarlandsConfig.verticalSimulationDistance * 2 + 1;
        return Mth.smallestEncompassingPowerOfTwo(gridSections) * 16 / 2;
    }

    /**
     * 盒的锚点：该轴的段原点，夹进"整盒仍落在 int 内"的可行窗口，再按 16 下取整。
     *
     * <p>{@code SectionPos.center()} 是 {@code origin + 8}，锚在段中心时盒端点余 8，切分面因此切进段
     * 内部。这里退回段原点：{@code halfSpanBlocks} 恒为 16 的倍数，所以端点、各级切分面与叶盒都落回段
     * 边界，每个叶盒恰好等于一段的体积。
     *
     * <p>上界取 {@code INT_MAX - half}：{@code AABB.of} 在 int 里做 {@code maxX + 1}，允许 {@code maxX}
     * 落到 {@code INT_MAX} 会溢出成 {@code INT_MIN}，AABB 从构造那刻起就是 {@code min > max} 的反序盒，
     * 进 {@code Frustum.cubeInFrustum} 判不可见，{@code visitNodes} 一个 section 都收不进八叉树。留一位
     * 之后 {@code maxX ≤ INT_MAX - 1}，加一后正好落在 int 内。
     *
     * <p>先夹后取整，顺序不能反：下界 {@code INT_MIN + half} 本身是 16 的倍数，下取整不会越过它；反过来
     * 则可能把原点推到下界之下。
     *
     * <p>锚点在 long 里由段中心减 8 得到，因为正极端的段中心本身已接近 int 顶端。
     */
    @Unique
    private static long farlands$alignedOrigin(int center) {
        long half = farlands$halfSpanBlocks();
        long origin = (long) center - 8L;
        long lowest = Integer.MIN_VALUE + half;
        long highest = (long) Integer.MAX_VALUE - half;
        return Mth.clamp(origin, lowest, highest) & ~15L;
    }

    /** 盒的下端。与 {@link #farlands$boxMax} 共用同一个原点，因此宽度恒为 2 * halfSpanBlocks()。 */
    @Unique
    private static int farlands$boxMin(int center) {
        return (int) (farlands$alignedOrigin(center) - farlands$halfSpanBlocks());
    }

    /** 盒的上端。由同一个原点派生，不单独取整，否则宽度会变。 */
    @Unique
    private static int farlands$boxMax(int center) {
        return (int) (farlands$alignedOrigin(center) + farlands$halfSpanBlocks() - 1L);
    }
}
