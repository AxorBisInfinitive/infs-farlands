package com.inf.farlands.simulation.int64.noise;

import com.inf.farlands.simulation.int64.util.LongBox;
import com.inf.farlands.simulation.int64.util.Sink;

/**
 * 按盒遍历方块，逐格求值并交出结果。
 *
 * <p>本类只做三件事：按 y、z、x 的顺序推进坐标，把坐标原样交给 {@link Density}，把返回值原样交给 {@link Sink}。
 * 不做算术、不做截断、不做插值、不改密度符号，所以它是透明的：同一个模型放在这里，收到的坐标与给出的值
 * 都不变。
 *
 * <p>不分配对象，也不缓存。上界需小于 {@code Long.MAX_VALUE}，等于它时自增回绕、循环不终止。
 */
public final class Sampler {

    private Sampler() {
    }

    public static void forEach(Density density, LongBox box, Sink sink) {
        for (long y = box.minY(); y < box.maxY(); y++) {
            for (long z = box.minZ(); z < box.maxZ(); z++) {
                for (long x = box.minX(); x < box.maxX(); x++) {
                    sink.accept(x, y, z, density.compute(x, y, z));
                }
            }
        }
    }
}
