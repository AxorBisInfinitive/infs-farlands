package com.inf.farlands.terrain;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 一段 section 的填充分派上下文。
 *
 * <p>
 * 段对象由填充器取好再交进来，系统不必也不许自己物化段。{@code baseX}、{@code baseY}、{@code baseZ} 是该段
 * 首个方块的绝对坐标：段内局部坐标 {@code (rx, ry, rz)} 对应 {@code (baseX + rx, baseY + ry, baseZ + rz)}。
 *
 * <p>
 * 允许读同 chunk 的其他段，但只有一条读法：{@link #sectionAt(int)}。它走段容器的查询，缺段即「那一段不存在」。
 * 不要用 {@code ChunkAccess.getSection}：那条路会按需建段，把读变成段的来源。
 */
public record SectionContext(ServerLevel level, ChunkAccess chunk, LevelChunkSection section,
        int sectionY, int baseX, int baseY, int baseZ) {

    /** 同 chunk 另一段的容器查询，缺段返回 null。不物化段。 */
    public LevelChunkSection sectionAt(int targetSectionY) {
        return ((WindowedChunk) this.chunk).windowedAllSections().get(targetSectionY);
    }
}
