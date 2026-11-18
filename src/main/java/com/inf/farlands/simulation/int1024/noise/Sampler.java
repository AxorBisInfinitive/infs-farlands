package com.inf.farlands.simulation.int1024.noise;

import com.inf.farlands.simulation.int1024.util.SectionBox;
import com.inf.farlands.simulation.int1024.util.Sink;
import love.crysh.long2.ints.Int1024;

/**
 * 按盒遍历方块，逐格求值并交出结果。
 *
 * <p>
 * 本类只做三件事：按 y、z、x 的顺序推进坐标，把坐标原样交给 {@link Density}，把返回值原样交给 {@link Sink}。
 * 不做算术、不做截断、不做插值、不改密度符号，所以它是透明的：同一个模型放在这里，收到的坐标与给出的值都不变。
 *
 * <p>
 * 坐标取自 {@link SectionBox} 预先算好的三组数组，索引是段内偏移 0 到 15，因此每格不再做一次加法分配。
 */
public final class Sampler {

    private Sampler() {
    }

    public static void forEach(Density density, SectionBox box, Sink sink) {
        for (int localY = 0; localY < SectionBox.SIZE; localY++) {
            Int1024 y = box.y(localY);
            for (int localZ = 0; localZ < SectionBox.SIZE; localZ++) {
                Int1024 z = box.z(localZ);
                for (int localX = 0; localX < SectionBox.SIZE; localX++) {
                    Int1024 x = box.x(localX);
                    sink.accept(x, y, z, density.compute(x, y, z));
                }
            }
        }
    }
}
