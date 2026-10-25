package com.inf.farlands.client.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.RayOcclusionSectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.Tree;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * isRayBlockedStepped 把段中心与相机位置都装进 float，再沿射线逐步推进采样点。float 的尾数只有
 * 24 位，2^24 之后整数不再逐个可表示，2^31 处相邻两个 float 相距 128 格，即 8 个 section。此时
 * {@code x += dX} 的增量远小于半个 ULP，加完被舍回原值，整条射线只落在一个 128 对齐点上，
 * blockHasObstruction 因此一直问同一个错的 section，LOCAL 树被判成大片遮挡。而 LOCAL 是
 * 剔除树里最窄的一棵，相机一停就会被选中，表现为停下后画面大面积消失。
 *
 * <p>
 * 这里用 double 重算同一个算法。double 的尾数 53 位，int 全域的方块坐标与逐步增量都精确，采样点
 * 落在数学上正确的位置。方法体与四个常量的取值照 Sodium 原版搬来，只把 float 换成 double，公式与
 * 分支一个不动。这两部分是本类的版本绑定面：原版改了算式或常量值，这里不会跟着变。
 *
 * <p>
 * 行为变化只有一处：采样点精度。原实现在正常坐标下已有的 float 误差被消除，采样点最多移动一个
 * float ULP，因此贴着 16 格边界的采样点可能换到相邻 section。差异的方向是更接近正确，但它确实是
 * 一处渲染行为变化，不藏在实现里。
 */
@Pseudo
@Mixin(RayOcclusionSectionTree.class)
public abstract class RayOcclusionSectionTreeMixin {

    @Shadow
    private CameraTransform transform;

    @Shadow
    private int blockHasObstruction(int x, int y, int z) {
        throw new AbstractMethodError();
    }

    /** 原版是同值 float，这里跟着换成 double。 */
    @Unique
    private static final double farlands$sectionHalfDiagonal = Math.sqrt(8 * 8 * 3);

    @Unique
    private static final double farlands$rayMinStepSizeInv = 1.0 / (farlands$sectionHalfDiagonal * 2);

    @Unique
    private static final int farlands$rayTestMaxSteps = 12;

    @Unique
    private static final int farlands$minRayTestDistanceSq = (int) Math.pow(16 * 3, 2);

    @Overwrite
    private boolean isRayBlockedStepped(RenderSection section) {
        double x = section.getCenterX();
        double y = section.getCenterY();
        double z = section.getCenterZ();
        double dX = this.transform.x - x;
        double dY = this.transform.y - y;
        double dZ = this.transform.z - z;

        double distanceSquared = dX * dX + dY * dY + dZ * dZ;
        if (distanceSquared < farlands$minRayTestDistanceSq) {
            return false;
        }

        double length = Math.sqrt(distanceSquared);
        int steps = Math.min((int) (length * farlands$rayMinStepSizeInv), farlands$rayTestMaxSteps);

        // 最后一步落在相机里，所以循环止于 steps 之前。
        double stepsInv = 1.0 / steps;
        dX *= stepsInv;
        dY *= stepsInv;
        dZ *= stepsInv;

        for (int i = 1; i < steps; i++) {
            x += dX;
            y += dY;
            z += dZ;

            // 该点不在已访问的段里，说明到相机之间没有通路。
            int result = this.blockHasObstruction((int) x, (int) y, (int) z);
            if (result == Tree.NOT_PRESENT) {
                // 再按半径补测八个角，避免采样间距造成的假阴性。
                double radius = farlands$sectionHalfDiagonal * (steps - i) * stepsInv;

                boolean hasPath = false;
                for (int corner = 0; corner < 8; corner++) {
                    double offsetX = ((corner & 1) == 0) ? -radius : radius;
                    double offsetY = ((corner & 2) == 0) ? -radius : radius;
                    double offsetZ = ((corner & 4) == 0) ? -radius : radius;

                    if (this.blockHasObstruction((int) (x + offsetX), (int) (y + offsetY),
                            (int) (z + offsetZ)) != Tree.NOT_PRESENT) {
                        hasPath = true;
                        break;
                    }
                }
                if (hasPath) {
                    continue;
                }

                return true;
            } else if (result == Tree.OUT_OF_BOUNDS) {
                break;
            }
        }

        return false;
    }
}
