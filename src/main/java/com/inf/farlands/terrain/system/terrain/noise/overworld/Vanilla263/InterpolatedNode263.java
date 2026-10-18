package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * 插值节点，对应 26.3 的 {@code InterpolatedFunction}。
 *
 * <p>26.3 的同一节点有两份实现，插值算式并不相同：标量那一份把八个角交给 {@code Mth.lerp3}，顺序是
 * x、y、z；缓冲那一份先沿 z 插值、再沿 x、最后 y 用步长累加。生成走缓冲，所以本类照缓冲写。
 *
 * <p>两条算式的差别不只是末几位。角点里出现相反符号的无穷时，先减哪一对决定哪一步先出
 * {@code Inf - Inf}，于是同一条地形在一条算式里整格成 NaN、在另一条里只在部分位置成 NaN。
 *
 * <p>标量那一份还在角点直接取内层值，缓冲那一份没有这条特判，角点也走同一套 lerp。角点取到无穷时，
 * 前者给出实心，后者给出 {@code NaN}。所以本类不设角点早退：留着它，格内是 NaN 而角点是方块，一格
 * 一个方块，看上去就是一张悬在空中的网格。
 *
 * <p>26.3 在 cell 角点上求值再插值，一个 4×8×4 的 cell 只算一次角点网格。移植体的根节点对 26.1.2
 * 是不透明的 {@code SimpleFunction}，{@code NoiseChunk} 建不出插值器，只能逐方块调进来，所以角点
 * 缓存必须由本类自己持有。
 *
 * <p>缓存键是上下文身份加 cell 原点。调用方按 cell 连续走一百二十八个方块，因此同一格只算一次角点，
 * 其余一百二十七次直接插值。换 chunk 即换上下文对象，不会读到上一块的角点。
 *
 * <p>角点上下文必须转发 blender。26.3 的角点采样走同一个采样上下文，若这里换成不带 blender 的
 * 普通上下文，blending 生效时八个角会全部按无 blending 取值。
 */
final class InterpolatedNode263 extends FloatNode263 {

    private final FloatNode263 input;
    private final int cellSizeXz;
    private final int cellSizeY;
    private final float cellSizeXzInv;
    private final float cellSizeYInv;
    private final ThreadLocal<Corners263> corners = ThreadLocal.withInitial(Corners263::new);

    InterpolatedNode263(FloatNode263 input, int cellSizeXz, int cellSizeY) {
        this.input = input;
        this.cellSizeXz = cellSizeXz;
        this.cellSizeY = cellSizeY;
        this.cellSizeXzInv = 1.0F / cellSizeXz;
        this.cellSizeYInv = 1.0F / cellSizeY;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        int blockX = context.blockX();
        int blockY = context.blockY();
        int blockZ = context.blockZ();
        int inX = Math.floorMod(blockX, this.cellSizeXz);
        int inY = Math.floorMod(blockY, this.cellSizeY);
        int inZ = Math.floorMod(blockZ, this.cellSizeXz);
        int baseX = blockX - inX;
        int baseY = blockY - inY;
        int baseZ = blockZ - inZ;
        Corners263 memo = this.corners.get();
        if (memo.context != context || memo.baseX != baseX || memo.baseY != baseY || memo.baseZ != baseZ) {
            this.fillCorners(context, baseX, baseY, baseZ, memo.values);
            memo.context = context;
            memo.baseX = baseX;
            memo.baseY = baseY;
            memo.baseZ = baseZ;
        }
        float[] v = memo.values;
        float alphaZ = inZ * this.cellSizeXzInv;
        float v00 = Mth.lerp(alphaZ, v[0], v[4]);
        float v01 = Mth.lerp(alphaZ, v[2], v[6]);
        float v10 = Mth.lerp(alphaZ, v[1], v[5]);
        float v11 = Mth.lerp(alphaZ, v[3], v[7]);
        float alphaX = inX * this.cellSizeXzInv;
        float v0 = Mth.lerp(alphaX, v00, v10);
        float v1 = Mth.lerp(alphaX, v01, v11);
        float valueStep = (v1 - v0) * this.cellSizeYInv;
        return v0 + valueStep * inY;
    }

    /** 八个角，顺序与 {@code lerp3} 的实参顺序一致：x 变化最快，y 其次，z 最后。 */
    private void fillCorners(DensityFunction.FunctionContext context, int baseX, int baseY, int baseZ,
            float[] out) {
        out[0] = this.sample(context, baseX, baseY, baseZ);
        out[1] = this.sample(context, baseX + this.cellSizeXz, baseY, baseZ);
        out[2] = this.sample(context, baseX, baseY + this.cellSizeY, baseZ);
        out[3] = this.sample(context, baseX + this.cellSizeXz, baseY + this.cellSizeY, baseZ);
        out[4] = this.sample(context, baseX, baseY, baseZ + this.cellSizeXz);
        out[5] = this.sample(context, baseX + this.cellSizeXz, baseY, baseZ + this.cellSizeXz);
        out[6] = this.sample(context, baseX, baseY + this.cellSizeY, baseZ + this.cellSizeXz);
        out[7] = this.sample(context, baseX + this.cellSizeXz, baseY + this.cellSizeY,
                baseZ + this.cellSizeXz);
    }

    private float sample(DensityFunction.FunctionContext context, int blockX, int blockY, int blockZ) {
        return this.input.computeF(new CornerContext(context, blockX, blockY, blockZ));
    }

    /** 一个线程上一个节点最后算过的那一格。用引用比较，不做哈希。 */
    private static final class Corners263 {

        private Object context;
        private int baseX;
        private int baseY;
        private int baseZ;
        private final float[] values = new float[8];
    }

    /** 坐标换成角点，其余全部转发，blender 尤其不能丢。 */
    private record CornerContext(DensityFunction.FunctionContext delegate, int blockX, int blockY, int blockZ)
            implements DensityFunction.FunctionContext {

        @Override
        public Blender getBlender() {
            return this.delegate.getBlender();
        }
    }
}
