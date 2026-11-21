package com.inf.farlands.simulation.int1024;

import com.inf.farlands.simulation.SimulationLevels;
import com.inf.farlands.simulation.model.SimulationSectionData;
import com.inf.farlands.simulation.model.SimulationSectionKey;
import com.inf.farlands.simulation.model.SimulationSectionStage;
import com.inf.farlands.simulation.model.SimulationStore;
import love.crysh.long2.ints.Int1024;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * 一段的填充分派上下文，与 terrain 的 {@code SectionContext} 同职责。
 *
 * <p>
 * 与那一份的差别只有三处，都是结构决定的。段号与块基址是 {@link Int1024}，没有 {@code sectionY} 这个 int；没有
 * chunk，所以读邻段走的不是 chunk 的容器表，而是主线程拍好的一份快照；群系没有第二入口，所以上下文自带
 * {@link #biomeAt}。
 *
 * <p>
 * 邻段只给群系容器，不给块容器。模拟层按段并行，邻段的块容器可能正被另一个池任务写，而 {@code LevelChunkSection}
 * 的块容器不是并发安全的。群系那一半安全：它由 {@code SimulationBiomeFiller} 写一次，写完档位才在主线程推到
 * {@link SimulationSectionStage#BIOME}，此后没有人再改它。
 *
 * <p>
 * 快照必须在主线程拍，因为 {@link SimulationStore} 的内层表是普通 map。槽位与客户端烘制那一套相同：
 * {@code (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)}，自身在 13。
 *
 * <p>
 * 邻段为 null 是常态而不是错误：窗口边缘的段常常还没过 {@code BIOME}，而模拟层没有重试。逐段系统要把 null 当
 * 「这一段不存在」。
 */
public record SimulationSectionContext(ServerLevel level, SimulationSectionKey key, LevelChunkSection section,
        Int1024 originX, Int1024 originY, Int1024 originZ,
        PalettedContainerRO<Holder<Biome>>[] biomes) {

    /** 邻域槽数，3x3x3。 */
    public static final int SLOTS = 27;

    /** 自分槽位。 */
    public static final int SELF = 13;

    /** 段内 quart 掩码，三轴各取低两位。 */
    private static final Int1024 QUART_MASK = Int1024.of(3L);

    private static final Int1024 ONE = Int1024.of(1L);

    private static final Int1024 NEGATIVE_ONE = Int1024.of(-1L);

    /** 越界哨兵，与 {@code -1} 到 {@code 1} 的合法偏移区分开。 */
    private static final int OUT_OF_RANGE = Integer.MIN_VALUE;

    public SimulationSectionContext {
        if (biomes.length != SLOTS) {
            throw new IllegalArgumentException("farlands: simulation section context needs " + SLOTS + " slots");
        }
    }

    /**
     * 主线程拍快照：27 个槽各查一次段表，只取已过 {@code BIOME} 的那一份群系容器。
     *
     * <p>
     * 段不在表里、或档位还低于 {@code BIOME} 时留 null，读侧按「不存在」处理。
     *
     * <p>
     * 地形系统不是逐段范式时返回 null：密度链那一支用不上这份快照，也就不付这 27 次查表。
     */
    public static PalettedContainerRO<Holder<Biome>>[] snapshot(ServerLevel level, SimulationSectionKey key) {
        if (!(((SimulationLevels) level).simulationTerrainSystem() instanceof SimulationSectionSystem)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        PalettedContainerRO<Holder<Biome>>[] out = new PalettedContainerRO[SLOTS];
        ResourceKey<Level> dimension = level.dimension();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int slot = (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
                    SimulationSectionData data = SimulationStore.get(dimension,
                            new SimulationSectionKey(key.x().add(Int1024.of((long) dx)),
                                    key.y().add(Int1024.of((long) dy)), key.z().add(Int1024.of((long) dz))));
                    if (data == null || data.stage() < SimulationSectionStage.BIOME) {
                        continue;
                    }
                    out[slot] = data.section.getBiomes();
                }
            }
        }
        return out;
    }

    /** 相邻段的群系容器，偏移各取 -1 到 1，自身在 0。越界或不在快照里返回 null。 */
    public PalettedContainerRO<Holder<Biome>> biomesAt(int dx, int dy, int dz) {
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
            return null;
        }
        return this.biomes[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)];
    }

    /**
     * 该 quart 的群系，只读段容器里的格值，不查参数表。
     *
     * <p>
     * 段内直接走本段容器；跨一段时走邻槽；超出 27 格或那一格没备，返回 null。本段的群系一定可读，因为档序保证
     * {@code BIOME} 先于 {@code NOISE}。
     */
    public Holder<Biome> biomeAt(Int1024 quartX, Int1024 quartY, Int1024 quartZ) {
        int dx = farlands$offset(quartX, this.key.x());
        int dy = farlands$offset(quartY, this.key.y());
        int dz = farlands$offset(quartZ, this.key.z());
        if (dx == OUT_OF_RANGE || dy == OUT_OF_RANGE || dz == OUT_OF_RANGE) {
            return null;
        }
        PalettedContainerRO<Holder<Biome>> cell = this.biomes[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)];
        if (cell == null) {
            return null;
        }
        return cell.get(quartX.and(QUART_MASK).toInt(), quartY.and(QUART_MASK).toInt(),
                quartZ.and(QUART_MASK).toInt());
    }

    /** quart 落在本段哪一侧：段号之差为 -1、0 或 1，超出即 {@link #OUT_OF_RANGE}。 */
    private static int farlands$offset(Int1024 quartCoord, Int1024 centreSection) {
        Int1024 delta = quartCoord.shift(-2).subtract(centreSection);
        if (delta.compareTo(ONE) > 0 || delta.compareTo(NEGATIVE_ONE) < 0) {
            return OUT_OF_RANGE;
        }
        return delta.toInt();
    }
}
