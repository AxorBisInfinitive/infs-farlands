package com.inf.farlands.serialize;

import java.util.Map;

import it.unimi.dsi.fastutil.shorts.ShortList;

/**
 * 生成期流体后处理的待办表宿主接口，由 ChunkAccessMixin 注入到 ChunkAccess。
 *
 * <p>
 * vanilla 把待结算的流体位置记进 ChunkAccess 的 postProcessing 数组，下标是窗口相对索引，而本 port 的
 * 服务端 chunk 构造时窗口只有一段，窗口之外的段下标即越界。这里另起一张按绝对 sectionY 索引的表，
 * 只活在内存里到 chunk 升到 ticking 为止，不进 NBT，因此不碰 postProcessing 数组与它的存读路径。
 *
 * <p>
 * 结算点是 LevelChunk.postProcessGeneration 的 HEAD。该方法的原版循环只读 postProcessing，对本 port
 * 恒为空表，于是流体写下去之后一次都不会被 tick，含水层边界的水因此不向相邻空腔铺开。
 */
public interface PendingFluidPostProcess {

    /** 换出全部待办并清表，按绝对 sectionY 分组。没有待办时返回空表。 */
    Map<Integer, ShortList> takePendingFluidPost();
}
