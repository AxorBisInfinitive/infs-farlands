package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.TintInvalidator;
import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.util.maps.Common;
import com.inf.farlands.util.window.WindowedChunk;

import io.netty.buffer.Unpooled;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * §5 窗口滑动 section 包的客户端接收链：到达时应用（chunk 未加载则缓存）、以及
 * chunk 加载后的缓存补应用。
 *
 * <p>type 注册在 main 源集的 {@code ChunkDataPacketRegister}——客户端同时跑 main/client
 * 两份 entrypoint，这里再注册一次会让 Commonbounds.gameplayBounds 出现重复 id，
 * CustomPacketPayload.codec 的 toUnmodifiableMap 会抛 Duplicate key。
 *
 * <p>「缓存补应用」的必要性：§5 到达时 chunk 可能尚未加载；直接丢弃会让方块数据
 * 永久缺失（服务端已出队不重发）——表现为空缺 / 双端不同步（客户端空预测下坠 vs
 * 服务端实心）。故缓存 {@code SectionEntry}，由
 * {@code ClientPacketListenerMixin.handleLevelChunkWithLight} RETURN 时补应用。
 *
 * <p>缓存结构在 main 源集 {@link Common}（只存 SectionEntry），本类负责施放——
 * 施放要 ClientLevel / ClientChunkCache，属客户端。
 */
public class ChunkDataPacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                ChunkDataPacket.TYPE,
                (payload, context) -> {
                    ClientLevel level = Minecraft.getInstance().level;
                    if (level == null) {
                        return;
                    }
                    // 维度校验：tp 跨维度在途旧包丢弃，防污染新维度
                    if (!level.dimension().equals(payload.dimension())) {
                        return;
                    }
                    int minY = payload.windowMinY();
                    LevelLightEngine le = level.getChunkSource().getLightEngine();
                    // 标脏按 chunk 的段区间合并一次。逐段调 setSectionDirtyWithNeighbors 会把同一个
                    // 3x3x3 盒重复标几十遍，详见 flushSectionDirty。
                    int curCx = Integer.MIN_VALUE;
                    int curCz = Integer.MIN_VALUE;
                    int curWinMin = 0;
                    int curWinMax = 0;
                    // hasInWindow 与两个端点同进同出：只有端点而没有这个标志时，单条项与首项最大这两种
                    // 形态会留下未赋值的一端，区间判空后一次都不标，数据到位而网格永不重建。
                    boolean hasInWindow = false;
                    int dirtyMinSy = 0;
                    int dirtyMaxSy = 0;
                    for (ChunkDataPacket.SectionEntry e : payload.sections()) {
                        ChunkAccess ca = level.getChunkSource().getChunk(
                                e.chunkX(), e.chunkZ(), ChunkStatus.FULL, false);
                        if (ca instanceof LevelChunk lc) {
                            applySectionData(level, le, lc, e, minY);
                            if (e.chunkX() != curCx || e.chunkZ() != curCz) {
                                // 先清颜色再标脏：本 chunk 的段已全部应用，邻居边缘列下次编译就能取到新群系。
                                invalidateTint(level, curCx, curCz);
                                flushSectionDirty(level, curCx, curCz, hasInWindow, dirtyMinSy, dirtyMaxSy,
                                        curWinMin, curWinMax);
                                curCx = e.chunkX();
                                curCz = e.chunkZ();
                                WindowedChunk wc = (WindowedChunk) lc;
                                curWinMin = wc.getWindowMinY();
                                curWinMax = wc.getWindowMaxY();
                                hasInWindow = false;
                                dirtyMinSy = 0;
                                dirtyMaxSy = 0;
                            }
                            int entrySy = e.sectionY();
                            if (entrySy < curWinMin || entrySy > curWinMax) {
                                // 窗口外的段不进区间，但必须逐条标脏：区间跨到窗口外时跨度会随会话历史
                                // 涨到上亿，而不标就会让数据到位后永不重建，Sodium 对已在集合里的段早退。
                                Minecraft.getInstance().levelRenderer.setSectionDirty(e.chunkX(), entrySy, e.chunkZ());
                            } else {
                                hasInWindow = true;
                                if (entrySy < dirtyMinSy) {
                                    dirtyMinSy = entrySy;
                                }
                                if (entrySy > dirtyMaxSy) {
                                    dirtyMaxSy = entrySy;
                                }
                            }
                        } else {
                            // chunk 未加载 → 缓存，chunk 加载后由 applyPendingSectionData 补应用
                            Common.cachePendingSectionData(level.dimension(),
                                    e.chunkX(), e.chunkZ(), minY, e);
                        }
                    }
                    invalidateTint(level, curCx, curCz);
                    flushSectionDirty(level, curCx, curCz, hasInWindow, dirtyMinSy, dirtyMaxSy,
                            curWinMin, curWinMax);
                });
    }

    /**
     * chunk 加载完成后补应用该 chunk 的 §5 缓存，由
     * {@code ClientPacketListenerMixin.handleLevelChunkWithLight} RETURN 调用。
     *
     * <p>注：26.1.2 侧尚无 §5 发送端（窗口差量 + 限量发包未移植），本方法目前只会
     * 在包头先到达、chunk 后加载的乱序场景下命中；发送端接入后即为常规路径。
     */
    public static void applyPendingSectionData(ClientLevel level, int cx, int cz) {
        Common.PendingSections pending = Common.takePendingSectionData(level.dimension(), cx, cz);
        if (pending == null) {
            return;
        }
        ChunkAccess ca = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (!(ca instanceof LevelChunk lc)) {
            return; // 仍未加载；补应用挂在 chunk 加载之后，理论不触发
        }
        LevelLightEngine le = level.getChunkSource().getLightEngine();
        WindowedChunk wc = (WindowedChunk) lc;
        int winMin = wc.getWindowMinY();
        int winMax = wc.getWindowMaxY();
        // 与主处理同一不变量：标志为真时两个端点都是真实段号。
        boolean hasInWindow = false;
        int dirtyMinSy = 0;
        int dirtyMaxSy = 0;
        for (ChunkDataPacket.SectionEntry e : pending.entries) {
            applySectionData(level, le, lc, e, pending.minY);
            int entrySy = e.sectionY();
            if (entrySy < winMin || entrySy > winMax) {
                // 同 §5 主处理：窗口外的段逐条标，不进区间。
                Minecraft.getInstance().levelRenderer.setSectionDirty(cx, entrySy, cz);
            } else {
                hasInWindow = true;
                if (entrySy < dirtyMinSy) {
                    dirtyMinSy = entrySy;
                }
                if (entrySy > dirtyMaxSy) {
                    dirtyMaxSy = entrySy;
                }
            }
        }
        invalidateTint(level, cx, cz);
        flushSectionDirty(level, cx, cz, hasInWindow, dirtyMinSy, dirtyMaxSy, winMin, winMax);
    }

    /**
     * 失效该 chunk 与其 3×3 邻域的方块染色缓存。
     *
     * <p>颜色按列缓存在 {@code ClientLevel} 上，缓存只在 chunk 加载完成与整表重载时失效。段包
     * 到达晚于 chunk 包，而 chunk 包那一次失效发生在空壳时刻，随后邻居边缘的列可能已按「对侧是
     * 空气」算过色并缓存；真段到达后若不补一次，那几列的颜色要等整表重载才会变。
     *
     * <p>只影响下一次编译取色，不触发重建。缓存自身按 chunk 记，{@code invalidateForChunk} 内部
     * 已经把 3×3 一起清掉，所以这里只传一个坐标。
     */
    private static void invalidateTint(ClientLevel level, int cx, int cz) {
        if (cx == Integer.MIN_VALUE || cz == Integer.MIN_VALUE) {
            return; // 批内还没出现过 chunk 时的占位值，不是坐标
        }
        if (level instanceof TintInvalidator tint) {
            tint.invalidateTintAround(cx, cz);
        }
    }

    /**
     * 按 chunk 的段区间标脏一次。
     *
     * <p>{@code setSectionDirtyWithNeighbors} 一次连带 3x3x3 共 27 个段（{@code LevelRenderer:1343-1345}），
     * 逐段调用会把同一个盒子重复标几十遍；而窗口内各段的盒子并集正好是这段连续区间，所以区间调一次
     * 即可，盒内每个目标恰好一次。
     *
     * <p>{@code hasInWindow} 为假表示本批没有窗口内条目，含 chunk 切换时的空批；为真时两个端点都是
     * 真实段号，且 {@code minSy <= maxSy}。端点不用哨兵值表示「无条目」，是为了让单条项与首项最大
     * 这两种形态不可能留下未赋值的一端。
     */
    private static void flushSectionDirty(ClientLevel level, int cx, int cz, boolean hasInWindow, int minSy,
            int maxSy, int winMin, int winMax) {
        if (!hasInWindow) {
            return;
        }
        // 标脏区间以目标 chunk 的当前窗口为界。若让区间跨到窗口外，待应用条目按 (维度, chunkPos) 累积、
        // tp 前后两段 Y 并存时跨度可达上亿；有 Sodium 时每格写一次侧信道表并与 trim 抢段锁，表会涨到
        // 千万级、渲染线程停等。窗口外的条目由调用方逐条 setSectionDirty 标，这里只负责窗口内那一段。
        minSy = Math.max(minSy, winMin);
        maxSy = Math.min(maxSy, winMax);
        if (minSy > maxSy) {
            return; // 夹取把区间夹空了，与「无条目」是两件事
        }
        level.setSectionRangeDirty(cx - 1, minSy - 1, cz - 1, cx + 1, maxSy + 1, cz + 1);
    }

    /** 应用单个 section 条目：数据、光照与持有边界。标脏由调用方按 chunk 区间合并后统一做。 */
    private static void applySectionData(ClientLevel level, LevelLightEngine le, LevelChunk lc,
            ChunkDataPacket.SectionEntry e, int minY) {
        WindowedChunk wc = (WindowedChunk) lc;
        wc.setLastPacketWindow(minY, minY + wc.windowHalfBelow() + wc.windowHalfAbove());
        // 不可变切换：新建 section 整体替换。PalettedContainer.read 的 createOrReuseData
        // 会复用现有 Data 原地改 palette——与渲染编译线程并发读时读到 palette 中间状态
        // → MissingPaletteEntryException CTD，重生窗口跳变场景下触发。新建对象无并发读者。
        LevelChunkSection ns = new LevelChunkSection(wc.containerFactory());
        ns.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(e.sectionData())));
        wc.windowedAllSections().put(e.sectionY(), ns);
        lc.getSection(lc.getSectionIndexFromSectionY(e.sectionY())); // 数组同步，get 内部执行 arr[idx]=s
        // 整体替换段对象绕开了 LevelChunk.setBlockState 的空/非空翻转通知，那条通知是 26.1.2
        // 的 loadedEmptySections 集合唯一的出栈路径。不补发，有内容的段会被 SectionOcclusionGraph
        // 的闸门永久钉成 EMPTY。
        //
        // 只对窗口内的段补发：该集合的进 addEmptySections 与出 dropEmptySections 都只遍历
        // chunk.getSections，即窗口数组，而 ClientChunkCache.Storage.inRange 只判 chunkX/Z、
        // 不判 sectionY。窗口外段若入集合，卸载时那条出栈路径遍历不到它，键只进不出，
        // 集合随会话单调增长，胀到 add 的容量算式回绕即 Index -1 out of bounds。
        // 窗口判定与第 100 行的 put 同步：同一段在窗口内才能入集合。
        LevelChunkSection inWindow = wc.windowedAllSections().get(e.sectionY());
        if (inWindow == ns) {
            level.getChunkSource().onSectionEmptinessChanged(e.chunkX(), e.sectionY(), e.chunkZ(),
                    ns.hasOnlyAir());
        }
        DataLayer bl = ChunkDataPacket.decodeLight(e.blockLight());
        if (bl != null) {
            le.queueSectionData(LightLayer.BLOCK, SectionPos.of(lc.getPos(), e.sectionY()), bl);
        }
        DataLayer sl = ChunkDataPacket.decodeLight(e.skyLight());
        if (sl != null) {
            le.queueSectionData(LightLayer.SKY, SectionPos.of(lc.getPos(), e.sectionY()), sl);
        }
    }
}
