package com.inf.farlands.simulation;

import com.inf.farlands.simulation.model.SimulationProducer;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 测试层：每 6 段一层，沿 x 走一条正弦高度场，从段底填到曲面。
 *
 * <p>
 * 内容与客户端原来那个 {@code grassPlane} 逐字相同，只是落点搬到了服务端：高度只随世界 x 变化，z 方向同高，
 * 相位用整数取模算，因为 worldX 可到十亿级以上，直接对 double 取 sin 会在参数归约上丢精度。基线与幅度之和落在
 * 0 到 14，不越出本段。形状取整格，与原来的 {@code SimulationBlock.full} 一致。
 */
public final class GrassPlaneProducer implements SimulationProducer {

    /** 正弦的周期，以方块计。32 格跨两个段，跨段接续看得出来。 */
    private static final int PERIOD = 32;

    /** 高度场的基线。与幅度相加后落在段内 0 到 14。 */
    private static final int BASE = 7;

    private static final int AMPLITUDE = 7;

    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();

    @Override
    public void produce(SimulationSectionKey key, SimulationSectionData data) {
        data.setShapeMode(SimulationSectionData.SHAPE_FULL);
        long baseX = key.x() * SimulationSectionData.SIZE;
        for (int x = 0; x < SimulationSectionData.SIZE; x++) {
            int phase = (int) Math.floorMod(baseX + x, (long) PERIOD);
            int height = BASE + (int) Math.round(AMPLITUDE * Math.sin(2.0 * Math.PI * phase / PERIOD));
            for (int z = 0; z < SimulationSectionData.SIZE; z++) {
                for (int y = 0; y <= height; y++) {
                    // 五参的 false：生产者跑在 farlands-sim 上，带检测那一档会取容器自己的线程检测器，而主线程
                    // 的打包与落盘编码也要取它。
                    data.section.setBlockState(x, y, z, GRASS, false);
                }
            }
        }
    }
}
