package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 记忆化节点，对应 26.3 的 {@code cache}。
 *
 * <p>26.3 的 cache 在编译期被去重成共享缓冲，所以它的 {@code compileSampler} 直接抛异常。它不只是
 * 省时间：整条链里 offset、factor、jaggedness、slopedCheese 都被它包住，一个方块内会被多个分支
 * 重复引用，不缓存就是三份样条各算好几遍。
 *
 * <p>这里的记忆化按线程与上下文身份共键。同一方块内重复求值命中；换一个 chunk 即换上下文对象，
 * 一定不命中，因此 blending 生效时不会读到上一块的混合结果。
 */
final class CacheNode263 extends FloatNode263 {

    private final FloatNode263 input;
    private final ThreadLocal<Memo263> memo = ThreadLocal.withInitial(Memo263::new);

    CacheNode263(FloatNode263 input) {
        this.input = input;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        Memo263 memoized = this.memo.get();
        int blockX = context.blockX();
        int blockY = context.blockY();
        int blockZ = context.blockZ();
        if (memoized.context == context && memoized.blockX == blockX && memoized.blockY == blockY
                && memoized.blockZ == blockZ) {
            return memoized.value;
        }
        float value = this.input.computeF(context);
        memoized.context = context;
        memoized.blockX = blockX;
        memoized.blockY = blockY;
        memoized.blockZ = blockZ;
        memoized.value = value;
        return value;
    }

    /** 一个线程上一个节点最后一笔读数。用引用比较，不做哈希，避免身份哈希碰撞。 */
    private static final class Memo263 {

        private Object context;
        private int blockX;
        private int blockY;
        private int blockZ;
        private float value;
    }
}
