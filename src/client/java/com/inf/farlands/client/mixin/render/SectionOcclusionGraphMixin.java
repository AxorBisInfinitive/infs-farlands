package com.inf.farlands.client.mixin.render;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;

import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 遮挡图洪泛的竖直上限对齐网格半高，以及远景直线校验的停转保护。
 *
 * <p>
 * 竖直上限：vanilla 的 getRelativeFrom 用水平视距当竖直上限，而渲染网格的竖直半径是
 * verticalSimulationDistance，两者不等时网格最外一圈永远遍历不到，那一圈不会进 octree，
 * 也就不进可见集，不渲染。
 *
 * <p>
 * 停转：{@code runUpdates} 的远景直线校验靠 {@code probe.add(step)} 前进，步长是
 * {@code ceil(sqrt(3) * 16)} 约 27.7 格。{@code probe} 的分量与相机同量级，而 double 的 ulp 在 `1e18` 处是
 * 128，在 `8.9e307` 处约 `2e292`，步长小于 ulp 时 add 是空操作，探点一步不动，距离恒大于 3600，
 * 循环只能靠 {@code probe.y} 越界退出。相机 Y 正常而 XZ 极端时那个出口也不成立，渲染线程与补全
 * 线程就都停在这一行上空转，表现为 CPU 高负载与整帧冻结。
 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {

    /** 上一次探到的点，三轴。每线程一份，检测「这一步没动」。 */
    @Unique
    private static ThreadLocal<double[]> farlands$lastProbe;

    @Redirect(method = "getRelativeFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;getViewDistance()I"))
    private static int farlands$gridHalfSpan(ViewArea viewArea) {
        return FarlandsConfig.verticalSimulationDistance;
    }

    /**
     * 探点与上一次完全相同时返回 {@code NaN}，让调用方的循环条件立刻为假而退出。
     *
     * <p>
     * 退出时该候选段按可见处理，方向是保守的：宁可不剔，不误剔。探点每步都会移动约 27 格，正常坐标下
     * 这个分支永不命中；也不需要按循环复位，因为新循环的起点不会等于上一个循环末尾的探点。
     *
     * <p>
     * 线程本地量是懒建的：{@code @Unique} 字段的初始化器不保证执行，两个线程同时首次进入时最多多建一份
     * 无用的状态，各自取值仍然正确。
     */
    @Redirect(method = "runUpdates", at = @At(value = "INVOKE",
            target = "Lorg/joml/Vector3d;distanceSquared(DDD)D"))
    private static double farlands$stalledRay(Vector3d probe, double x, double y, double z) {
        double distance = probe.distanceSquared(x, y, z);
        if (farlands$lastProbe == null) {
            farlands$lastProbe = new ThreadLocal<>();
        }
        double[] last = farlands$lastProbe.get();
        if (last != null && last[0] == probe.x && last[1] == probe.y && last[2] == probe.z) {
            return Double.NaN;
        }
        if (last == null) {
            last = new double[3];
            farlands$lastProbe.set(last);
        }
        last[0] = probe.x;
        last[1] = probe.y;
        last[2] = probe.z;
        return distance;
    }
}
