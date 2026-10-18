package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 二元节点，对应 26.3 的 {@code BinaryFunction} 六种类型。
 *
 * <p>乘法与除法不取左零短路。26.3 的同一节点有两份实现，{@code sampleValue} 的乘除带
 * {@code left == 0 ? 0 : 运算}，{@code sampleVolume} 的不带；生成走缓冲那一份，所以真实语义是不短路。
 *
 * <p>两者只在右值非有限时分开：短路会把 {@code 0 * NaN} 折成 0，不短路则把 NaN 传下去。本链在极端 X
 * 处 JAGGED 噪声会给出 NaN，那一支必须传下去。折成 0 的后果是整片地形留在地面，与 26.3 的虚空不同，
 * 而且看不出是哪里动了手。
 *
 * <p>最小值与最大值无条件求两侧，同样跟缓冲那一份。标量那一份带范围短路，短路成立时根本不求右值，
 * 右值若是 NaN 就会被吞掉；那不是生成时的语义。
 */
final class BinaryNode263 extends FloatNode263 {

    enum Type {
        ADD,
        SUB,
        MUL,
        DIV,
        MIN,
        MAX
    }

    private final Type type;
    private final FloatNode263 left;
    private final FloatNode263 right;

    BinaryNode263(Type type, FloatNode263 left, FloatNode263 right) {
        this.type = type;
        this.left = left;
        this.right = right;
    }

    @Override
    float computeF(DensityFunction.FunctionContext context) {
        float leftValue = this.left.computeF(context);
        switch (this.type) {
            case ADD:
                return leftValue + this.right.computeF(context);
            case SUB:
                return leftValue - this.right.computeF(context);
            case MUL:
                return leftValue * this.right.computeF(context);
            case DIV:
                return leftValue / this.right.computeF(context);
            case MIN:
                return Math.min(leftValue, this.right.computeF(context));
            case MAX:
                return Math.max(leftValue, this.right.computeF(context));
            default:
                throw new IllegalStateException("farlands: unknown binary type " + this.type);
        }
    }
}
