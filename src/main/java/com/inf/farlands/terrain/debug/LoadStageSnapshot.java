package com.inf.farlands.terrain.debug;

import com.inf.farlands.serialize.ChunkCounters;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 加载页的网格读数快照：服务端主线程拍，客户端渲染线程读。
 *
 * <p>页面每帧要问每一格的状态，而状态住在 SectionStage 的分段 map 里，读它要拿锁；渲染线程不许拿服务端的
 * 锁。所以这里按墙钟限流拍成不可变快照，volatile 发布一次，读侧零锁、零服务端对象。
 *
 * <p>格子语义取判据段带里最慢的那一段，不取最快的那一段。这一页等的不是跑到哪了，而是还差什么；取最低档
 * 只在全部段都推进时前进，天然单调，不会因为一段抢跑而整格提前变白。
 *
 * <p>八档：0 到 6 是 SectionStage 的 UNPROCESSED 到 LIGHTED，7 表示该 chunk 的 FULL 已补。第 7 档只读
 * {@link ChunkCounters#isFullDone}，不碰 {@link ChunkCounters#claimFull} 的令牌，否则读一次就把补发吃掉。
 *
 * <p>壳在场但段带里没有任何段的格，记 0 而不是 NO_SHELL。两者不是一件事：前者是壳已就位、还没轮到，后
 * 者是这一格根本不存在。混成一个值会让票圈外那几圈看起来和虚空一样。
 */
public final class LoadStageSnapshot {

    /** 这一格没有壳。客户端按原版的 null 上色，即黑色。 */
    public static final byte NO_SHELL = -1;

    /** 有壳，但段带里一个可玩段都没有。 */
    public static final byte NO_SECTION = 0;

    /** FULL 已补的档位，第 8 档。 */
    public static final byte FULL_CLAIMED = (byte) (SectionStage.LIGHTED + 1);

    /**
     * 发布间隔，墙钟毫秒。
     *
     * <p>页面只需要几赫兹，而一次发布要遍历整个网格。挂在轮数上会让建世界期那台泵按它的快节奏翻倍付成本，
     * 所以按墙钟限流。
     */
    private static final long PUBLISH_INTERVAL_MILLIS = 250L;

    /**
     * 一帧读数。
     *
     * @param centerCx 中心 chunk 的 x
     * @param centerCz 中心 chunk 的 z
     * @param radius   网格半径
     * @param cells    边长 2*radius+1 的方阵，行主序，值是档位、NO_SECTION 或 NO_SHELL
     */
    public record Snapshot(int centerCx, int centerCz, int radius, byte[] cells) {

        public Snapshot {
            int side = radius * 2 + 1;
            if (cells.length != side * side) {
                throw new IllegalArgumentException(
                        "farlands: cell array does not match radius " + radius + ": " + cells.length);
            }
        }

        public int side() {
            return this.radius * 2 + 1;
        }

        /** 该格的档位，越界返回 NO_SHELL。 */
        public byte cell(int x, int z) {
            int side = side();
            if (x < 0 || z < 0 || x >= side || z >= side) {
                return NO_SHELL;
            }
            return this.cells[z * side + x];
        }
    }

    private static volatile Snapshot current;

    /** 上次发布时刻，只在主线程读写。 */
    private static long lastPublishMillis;

    private LoadStageSnapshot() {
    }

    /** 当前快照，没有则为 null。任意线程可调。 */
    public static Snapshot current() {
        return current;
    }

    /** 卸关卡时清空。客户端主线程调用。 */
    public static void clear() {
        current = null;
    }

    /**
     * 拍一份快照，服务端主线程调用，专用服务器直接返回。
     *
     * <p>网格半径不在这里推，由调用方把它自己的票据半径给进来；页面据此画同样大的网格，直径与 cells 同源。
     *
     * @param gridRadius 网格半径
     */
    public static void publish(ServerLevel level, ChunkPos center, int minSy, int maxSy, int gridRadius) {
        MinecraftServer server = level.getServer();
        if (server == null || server.isDedicatedServer()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) {
            return;
        }
        lastPublishMillis = now;

        int side = gridRadius * 2 + 1;
        byte[] cells = new byte[side * side];
        for (int x = 0; x < side; x++) {
            int cx = center.x() - gridRadius + x;
            for (int z = 0; z < side; z++) {
                int cz = center.z() - gridRadius + z;
                LevelChunk chunk = SectionLifecycle.latestChunk(level, cx, cz);
                if (chunk == null) {
                    cells[z * side + x] = NO_SHELL;
                    continue;
                }
                int lowest = Integer.MAX_VALUE;
                for (int sy = minSy; sy <= maxSy; sy++) {
                    if (!WorldBounds.inSection(sy)) {
                        continue;
                    }
                    int stage = SectionStage.getStage(chunk, sy);
                    if (stage < lowest) {
                        lowest = stage;
                    }
                }
                if (lowest == Integer.MAX_VALUE) {
                    cells[z * side + x] = NO_SECTION;
                } else if (lowest >= SectionStage.LIGHTED) {
                    // 七段全亮之后还差一步 FULL 已补。未补则停在 LIGHTED，页面据此区分数据就绪与已放出。
                    cells[z * side + x] = ChunkCounters.isFullDone(chunk) ? FULL_CLAIMED
                            : (byte) SectionStage.LIGHTED;
                } else {
                    cells[z * side + x] = (byte) lowest;
                }
            }
        }
        current = new Snapshot(center.x(), center.z(), gridRadius, cells);
    }
}
