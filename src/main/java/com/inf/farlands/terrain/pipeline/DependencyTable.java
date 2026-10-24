package com.inf.farlands.terrain.pipeline;

import java.util.Map;

import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 三处依赖判据：票位到允许档的换算、段集合的欠账计数、装饰写域的九格门。
 *
 * <p>
 * 版本绑定：vanilla 的 {@code ChunkStatus} 常量名与 {@code ChunkLevel} 的算式取自
 * 26.1.2，升级要重核。
 *
 * <p>
 * 档位之间确实有依赖，但那份依赖现在只活在三处判据里，各自按自己问的问题取形：准入问「这个候选的
 * 段集合里还欠几段」，装饰问「九格都过雕刻了吗」，票位问「这一格最多许到哪一档」。本类不再另存一份
 * 按行划分的依赖声明。那份声明描述的是按 chunk 整块推进的旧模型，与现行的段集合准入不同量纲，
 * 留着只会与实现对不上。
 *
 * <p>
 * 欠账的档位关系，按实际行为写在这里：{@code BIOMES} 无前置；{@code TERRAIN} 要 biome；{@code SURFACE}
 * 要 terrain；{@code CARVERS} 要 surface；{@code DECORATED} 要中心与八个邻居都过 carvers；{@code LIGHTED}
 * 要 decorated；补 FULL 只要不欠点亮，不要求 decorate 之外还有别的。
 */
public final class DependencyTable {

    /** 我们的档到 vanilla 状态的映射，顺序即档位顺序。 */
    private static final ChunkStatus[] STATUS_OF_STAGE = {
            ChunkStatus.EMPTY,
            ChunkStatus.BIOMES,
            ChunkStatus.NOISE,
            ChunkStatus.SURFACE,
            ChunkStatus.CARVERS,
            ChunkStatus.FEATURES,
            ChunkStatus.LIGHT,
    };

    private DependencyTable() {
    }

    /**
     * 该票位级允许推进到的最高档。
     *
     * <p>
     * 取值域是 {@code [-1, 6]}：-1 表示票位已超出 {@code ChunkLevel.MAX_LEVEL}，即该 chunk 未加载，
     * 任何档都不许；0 表示只许停在 {@code UNPROCESSED}，即不许开工。判据是 vanilla 的
     * {@code generationStatus} 与 {@code ChunkStatus.isOrBefore}，不写死 33 与半径。
     */
    public static int allowedStage(int ticketLevel) {
        if (ticketLevel < 0) {
            return -1;
        }
        ChunkStatus allowed = ChunkLevel.generationStatus(ticketLevel);
        if (allowed == null) {
            return -1;
        }
        for (int stage = STATUS_OF_STAGE.length - 1; stage >= 0; stage--) {
            if (STATUS_OF_STAGE[stage].isOrBefore(allowed)) {
                return stage;
            }
        }
        return -1;
    }

    /**
     * 给定段集合里档位低于 {@code limit} 的段数。
     *
     * <p>集合由 {@code Scheduler.sectionsFor} 产出，已按可玩界与在读过滤并升序去重。准入的欠账判据与
     * 第七行的完成判据都走这里，两个判据因此与 {@code GenTask} 的收集读同一份段集合。
     *
     * <p>三处调用各自换 {@code limit}：生成支用 {@code CARVERS}，第七行补 FULL 用 {@code LIGHTED}，
     * 视距内兜底重扫用 {@code CARVERS}。补 FULL 不要求「不欠 decorated」，它只要求整窗段都已点亮，
     * 不欠点亮就是可以放出。
     */
    public static int pendingBelowIn(LevelChunk chunk, int[] sections, int limit) {
        int count = 0;
        for (int sy : sections) {
            if (SectionStage.getStage(chunk, sy) < limit) {
                count++;
            }
        }
        return count;
    }

    /**
     * 装饰的写域九格门：每一格都在场、都有已物化的 section、且已有 section 都到 {@code CARVERS}，
     * 在读的 section 除外。不可玩界的邻居只要求在场。
     *
     * <p>判据面取档位表与段容器的交集，两头都不能放宽：
     *
     * <ul>
     * <li>只遍历容器会把构造期的窗口默认段算成 {@code UNPROCESSED}。那些段被物化但从不落档，本来
     * 就不在该被生成的集合里，按容器判会让九格恒假。</li>
     * <li>只遍历档位表会把读回标记留下的残留算进来。那些条目容器里没有，装饰也写不进去，
     * 按整表判会让周围九格的装饰被永久挡死。</li>
     * </ul>
     *
     * <p>交集与写门同宽：{@code DecorationRegion.setBlock} 对未物化的段直接返回假。这条是一条耦合，
     * 若将来写门改成会物化的取法，例如 {@code getSection}，判据面要重新算。
     *
     * <p>九格等于写域 ±1，与 {@code DecorationFiller} 的认领半径、{@code DecorationRegion} 的写域半径
     * 是同一个数，三处各写一遍。
     */
    public static boolean neighborGate(LevelChunk center) {
        if (!(center.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        ChunkPos pos = center.getPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = pos.x() + dx;
                int cz = pos.z() + dz;
                if (!cellReady(cursor.at(cx, cz), cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 九格里某一格是否可过：在场、可玩界、可写集合非空、且其中没有低于 {@code CARVERS} 的段。 */
    private static boolean cellReady(LevelChunk neighbor, int cx, int cz) {
        if (neighbor == null) {
            return false;
        }
        if (!WorldBounds.inChunkRange(cx, cz)) {
            return true;
        }
        int[] count = { 0 };
        boolean[] below = { false };
        Map<Integer, LevelChunkSection> container = ((WindowedChunk) neighbor).windowedAllSections();
        SectionStage.forEachStage(neighbor, (sy, stage) -> {
            if (sy == null || !container.containsKey(sy)) {
                return;
            }
            count[0]++;
            if (stage < SectionStage.CARVERS && !SectionIO.isReading(neighbor, sy)) {
                below[0] = true;
            }
        });
        return count[0] > 0 && !below[0];
    }
}
