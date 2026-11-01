package com.inf.farlands.client.mixin.render;

import com.inf.farlands.client.render.AnchoredFrustum;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.joml.FrustumIntersection;
import org.joml.Vector4f;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 相机参考改成「锚点 long 加偏移 double」，让视锥的取整与退让在任何量级都成立。
 *
 * <p>
 * 原版把相机世界坐标存成三个绝对 double，而所有可见性判定都是「世界坐标减这三个数」再落 float。
 * 那个减法在 2^53 以上仍然精确，坏的是另外两处：{@code offsetToFullyIncludeCameraCube} 每次后退
 * {@code OFFSET_STEP} 格，这个步长在大坐标上小于 double 的 ulp，后退量被舍回原值，循环永不退出；
 * 同时 {@code floor(cam / cubeSize) * cubeSize} 与 {@code cam} 相等，8 格立方退化成零体积，
 * 近平面必然拒绝它，于是循环一定要跑。两件事叠起来就是渲染线程的原地死循环。
 *
 * <p>
 * 这里把三个字段的含义换成相对锚点的偏移，锚点取 8 的倍数。所有差值因此都是「世界减锚点」再减偏移：
 * 前一项是两个相邻量级 double 的差，2 倍以内按 Sterbenz 精确；后一项绝对值小于 8。后退量作用在偏移上，
 * 大坐标上照样能走，循环像原版一样一两轮退出。
 *
 * <p>
 * 外部读到的仍是绝对坐标，{@code getCamX} 一族把锚点加回去，{@code ChunkCullingDebugRenderer} 不受影响。
 *
 * <p>
 * 拷贝路径要单独同步锚点，见 {@link #farlands$copyAnchors}：原版 {@code set} 只拷那三个分量，而它们现在
 * 是偏移，锚点若不跟着拷，跨维度与捕捉视锥之后两侧的参考点就错开了。
 */
@Mixin(Frustum.class)
public abstract class FrustumMixin implements AnchoredFrustum {

    @Shadow
    @Final
    private FrustumIntersection intersection;

    @Shadow
    private Vector4f viewVector;

    @Shadow
    private double camX;

    @Shadow
    private double camY;

    @Shadow
    private double camZ;

    @Shadow
    @Final
    public static int OFFSET_STEP;

    /** 锚点三轴，8 的倍数。 */
    @Unique
    private long farlands$anchorX;

    @Unique
    private long farlands$anchorY;

    @Unique
    private long farlands$anchorZ;

    @Override
    public long farlands$anchorX() {
        return this.farlands$anchorX;
    }

    @Override
    public long farlands$anchorY() {
        return this.farlands$anchorY;
    }

    @Override
    public long farlands$anchorZ() {
        return this.farlands$anchorZ;
    }

    /** 相机就位：拆成锚点与偏移。 */
    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private void farlands$prepare(double x, double y, double z, CallbackInfo ci) {
        this.farlands$anchorX = farlands$anchor(x);
        this.farlands$anchorY = farlands$anchor(y);
        this.farlands$anchorZ = farlands$anchor(z);
        this.camX = x - (double) this.farlands$anchorX;
        this.camY = y - (double) this.farlands$anchorY;
        this.camZ = z - (double) this.farlands$anchorZ;
        ci.cancel();
    }

    /** 原版体只拷偏移，锚点在这里补。 */
    @Inject(method = "set", at = @At("RETURN"))
    private void farlands$copyAnchors(Frustum source, CallbackInfo ci) {
        AnchoredFrustum anchored = (AnchoredFrustum) source;
        this.farlands$anchorX = anchored.farlands$anchorX();
        this.farlands$anchorY = anchored.farlands$anchorY();
        this.farlands$anchorZ = anchored.farlands$anchorZ();
    }

    /** 沿视线平移相机，只动偏移。 */
    @Inject(method = "offset", at = @At("HEAD"), cancellable = true)
    private void farlands$offset(float offset, CallbackInfoReturnable<Frustum> cir) {
        this.camX = this.camX + this.viewVector.x * offset;
        this.camY = this.camY + this.viewVector.y * offset;
        this.camZ = this.camZ + this.viewVector.z * offset;
        cir.setReturnValue((Frustum) (Object) this);
    }

    /**
     * 把相机后退到那个立方完全落进视锥。后退量记在 {@code back} 里，作用在偏移上，退出时一次写回。
     *
     * <p>
     * 六个端点是「对齐到 {@code cubeSize} 的格点，减去循环开始时的相机」，即相机相对量；循环里再叠加
     * {@code v * back} 这一项平移。减相机不能省：循环结束后相机是 {@code cam0 - v * back}，所以循环最后测的
     * 盒，与之后 {@code cubeInFrustum} 对同一个盒算的是同一个表达式。少了它，循环测的是被平移了
     * {@code cam0} 的盒，会在真实盒仍露在视锥外时退出。
     *
     * <p>
     * 这要求 {@code cubeSize} 整除锚点的对齐单位 8，唯一调用点 {@code LevelRenderer.offsetFrustum} 传的就是 8。
     *
     * <p>
     * 后退步数有上限。原版的条件是「测到 INSIDE 为止」，而相机非有限时六个端点是 {@code NaN}，判据永远给不出
     * INSIDE，循环不会退出，渲染线程就此停在这一行。上限只约束步数，正常相机在个位数步内就退出，结果与不设上限
     * 逐位相同；真的跑到上限时不动相机，因为此时没有任何步数是对的。
     */
    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"), cancellable = true)
    private void farlands$offsetToFullyIncludeCube(int cubeSize, CallbackInfoReturnable<Frustum> cir) {
        double minX = Math.floor(this.camX / cubeSize) * cubeSize - this.camX;
        double minY = Math.floor(this.camY / cubeSize) * cubeSize - this.camY;
        double minZ = Math.floor(this.camZ / cubeSize) * cubeSize - this.camZ;
        double maxX = Math.ceil(this.camX / cubeSize) * cubeSize - this.camX;
        double maxY = Math.ceil(this.camY / cubeSize) * cubeSize - this.camY;
        double maxZ = Math.ceil(this.camZ / cubeSize) * cubeSize - this.camZ;
        double back = 0.0;
        boolean inside = false;
        // 4096 步等于 16384 格后退，是远平面上限的四倍，正常相机在个位数步内就会 INSIDE。
        // 上限的存在只为终止：相机非有限时端点恒为 NaN，任何步数都到不了 INSIDE。
        for (int step = 0; step < 4096; step++) {
            int result = this.intersection.intersectAab(
                    (float) (minX + this.viewVector.x * back),
                    (float) (minY + this.viewVector.y * back),
                    (float) (minZ + this.viewVector.z * back),
                    (float) (maxX + this.viewVector.x * back),
                    (float) (maxY + this.viewVector.y * back),
                    (float) (maxZ + this.viewVector.z * back));
            if (result == -2) {
                inside = true;
                break;
            }
            back += OFFSET_STEP;
        }
        if (inside) {
            this.camX = this.camX - this.viewVector.x * back;
            this.camY = this.camY - this.viewVector.y * back;
            this.camZ = this.camZ - this.viewVector.z * back;
        }
        cir.setReturnValue((Frustum) (Object) this);
    }

    /** 可见性判定：世界坐标先减锚点，再减偏移。 */
    @Inject(method = "cubeInFrustum(DDDDDD)I", at = @At("HEAD"), cancellable = true)
    private void farlands$cubeInFrustum(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(farlands$cube(minX, minY, minZ, maxX, maxY, maxZ));
    }

    @Inject(method = "pointInFrustum(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void farlands$pointInFrustum(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.intersection.testPoint(
                (float) ((x - (double) this.farlands$anchorX) - this.camX),
                (float) ((y - (double) this.farlands$anchorY) - this.camY),
                (float) ((z - (double) this.farlands$anchorZ) - this.camZ)));
    }

    @Inject(method = "getCamX", at = @At("HEAD"), cancellable = true)
    private void farlands$getCamX(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue((double) this.farlands$anchorX + this.camX);
    }

    @Inject(method = "getCamY", at = @At("HEAD"), cancellable = true)
    private void farlands$getCamY(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue((double) this.farlands$anchorY + this.camY);
    }

    @Inject(method = "getCamZ", at = @At("HEAD"), cancellable = true)
    private void farlands$getCamZ(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue((double) this.farlands$anchorZ + this.camZ);
    }

    /** 盒端点的加一改在 double 里做，理由见下。 */
    @Inject(method = "cubeInFrustum(Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)I", at = @At("HEAD"), cancellable = true)
    private void farlands$wideCube(BoundingBox bb, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(farlands$cube(bb.minX(), bb.minY(), bb.minZ(),
                (double) bb.maxX() + 1.0, (double) bb.maxY() + 1.0, (double) bb.maxZ() + 1.0));
    }

    @Override
    public boolean farlands$isVisibleRelative(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        int result = this.intersection.intersectAab((float) minX, (float) minY, (float) minZ,
                (float) maxX, (float) maxY, (float) maxZ);
        return result == FrustumIntersection.INSIDE || result == FrustumIntersection.INTERSECT;
    }

    /**
     * {@code BoundingBox} 端点的加一改在 double 里做。
     *
     * <p>
     * 原版把端点直接转给六参重载，其中三个上端点先做 int 的 {@code + 1}。八叉树的根盒端点由
     * {@code OctreeMixin} 放到了 {@code INT_MAX}，那一步之后 {@code INT_MAX + 1} 会回绕成
     * {@code INT_MIN}，盒从构造那刻起就是反序。对 {@code maxX ≤ INT_MAX - 1} 与 int 版逐位等价。
     */
    @Unique
    private int farlands$cube(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return this.intersection.intersectAab(
                (float) ((minX - (double) this.farlands$anchorX) - this.camX),
                (float) ((minY - (double) this.farlands$anchorY) - this.camY),
                (float) ((minZ - (double) this.farlands$anchorZ) - this.camZ),
                (float) ((maxX - (double) this.farlands$anchorX) - this.camX),
                (float) ((maxY - (double) this.farlands$anchorY) - this.camY),
                (float) ((maxZ - (double) this.farlands$anchorZ) - this.camZ));
    }

    /** 锚点取 8 的倍数，偏移因此落在 [0, 8)。 */
    @Unique
    private static long farlands$anchor(double value) {
        return (long) (Math.floor(value / 8.0) * 8.0);
    }
}
