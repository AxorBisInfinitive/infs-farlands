package com.inf.farlands.util.window;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.pipeline.GenQueue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 服务端发包线程的 per-player 窗口状态，存于 ThreadLocal。
 *
 * PlayerChunkSenderMixin 在 PlayerChunkSender.sendChunk 前后设置与清除，包体构造据此决定发送
 * 范围。发送是服务端主线程串行的，ThreadLocal 设与清成对；sendChunk 循环内每个 chunk 重新设置
 * 同玩家时值相同，无害。异常路径的残留会被下一次 HEAD 覆盖。
 */
public final class WindowSendState {
    private WindowSendState() {
    }

    private static final ThreadLocal<Integer> WINDOW_MIN_Y = new ThreadLocal<>();

    public static void setWindowMinY(int minY) {
        WINDOW_MIN_Y.set(minY);
    }

    public static void clear() {
        WINDOW_MIN_Y.remove();
    }

    /** 发送范围取 ThreadLocal 窗口，即发送时刻的玩家窗口；无则回退 chunk 自身的窗口下界。 */
    private static int windowMinY(LevelChunk chunk) {
        Integer v = WINDOW_MIN_Y.get();
        return v != null ? v : ((WindowedChunk) chunk).getWindowMinY();
    }

    /**
     * 窗口内 section 列表，按绝对 sectionY 升序。calculateChunkSize 与 extractChunkData 必须共用
     * 同一入口，否则 buffer 尺寸与实际写入不匹配，26.1.2 会在写满断言上抛异常。
     *
     * 纯空气段照发：群系存在段里，段不发客户端就没有那份群系，读到的会是工厂默认群系。空气段的
     * 段体只是空方块容器加群系容器，各几字节，窗口最多 2 * verticalSimulationDistance + 1 段。
     *
     * fill 或光照在途即 GenQueue.isGeneratingOrLighting 的 chunk 返回空列表：它们并发写 section 时
     * 不取包锁，两次遍历之间的 section 集合与内容会不一致；过滤后 section 稳定才打包。空出来的数据由
     * fill 完成后的段包补齐。
     *
     * <p>装饰不在此列：它的段写取 {@code SectionSerializer.packLockFor}，与序列化互斥，所以装饰在途的
     * chunk 照常按段下发，客户端不会在装饰期间看到整片冻结。
     *
     * <p>短路完成后的 biome 阶段跑在 Util.backgroundExecutor 上，不在那两张标志内，所以另查
     * GenQueue.isBiomeFilling，它在两次遍历之间同样会换掉 section 的 biomes 容器。
     *
     * <p>与落盘同门：只收 stage 已到 LIGHTED 的段，低于它的段还在中间态。把中间态发出去，客户端会
     * 先显示一份随后要被推翻的地形，而服务端仍按未就绪拒绝放置与破坏，那正是看得见地形却动不了的
     * 来源。空段不受这道门约束，群系存在段里，不发客户端就没有那份群系。
     */
    public static List<Map.Entry<Integer, LevelChunkSection>> sendableSections(LevelChunk chunk) {
        if (GenQueue.isGeneratingOrLighting(chunk) || GenQueue.isBiomeFilling(chunk)) {
            return List.of();
        }
        int minY = windowMinY(chunk);
        int maxY = minY + FarlandsConfig.verticalSimulationDistance * 2;
        List<Map.Entry<Integer, LevelChunkSection>> out = new ArrayList<>();
        for (Map.Entry<Integer, LevelChunkSection> e : ((WindowedChunk) chunk).windowedAllSections().entrySet()) {
            int sy = e.getKey();
            LevelChunkSection s = e.getValue();
            if (sy >= minY && sy <= maxY && s != null
                    && SectionStage.isTerminal(SectionStage.getStage(chunk, sy))) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt(Map.Entry::getKey));
        return out;
    }
}
