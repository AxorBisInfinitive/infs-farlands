package com.inf.farlands.client.mixin.expand.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.light.FarLandsLightPacketData;
import com.inf.farlands.util.maps.Common;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.PacketUtils;

@Mixin(value = ClientPacketListener.class, priority = 100)
public abstract class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Overwrite
    public void handleChunksBiomes(ClientboundChunksBiomesPacket packet) {
        PacketUtils.ensureRunningOnSameThread(
                packet,
                (ClientPacketListener) (Object) this,
                Minecraft.getInstance().packetProcessor());

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            this.level
                    .getChunkSource()
                    .replaceBiomes(data.pos().x(), data.pos().z(), data.getReadBuffer());
        }

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            this.level.onChunkLoaded(new ChunkPos(data.pos().x(), data.pos().z()));
        }

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    ChunkAccess ca = this.level.getChunkSource().getChunk(
                            data.pos().x() + i, data.pos().z() + j, ChunkStatus.FULL, false);
                    if (ca instanceof LevelChunk c) {
                        for (Integer sectionY : ((WindowedChunk) c).windowedAllSections().keySet()) {
                            Minecraft.getInstance().levelRenderer.setSectionDirty(
                                    data.pos().x() + i, sectionY, data.pos().z() + j);
                        }
                    }
                }
            }
        }
    }

    @Overwrite
    public void handleForgetLevelChunk(ClientboundForgetLevelChunkPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet,
                (ClientPacketListener) (Object) this, Minecraft.getInstance().packetProcessor());
        ChunkPos cpos = packet.pos();

        int[] sectionYs = null;
        ChunkAccess ca = this.level.getChunkSource().getChunk(cpos.x(), cpos.z(), ChunkStatus.FULL, false);
        if (ca instanceof LevelChunk c) {
            java.util.Set<Integer> keys = ((WindowedChunk) c).windowedAllSections().keySet();
            sectionYs = new int[keys.size()];
            int idx = 0;
            for (Integer sy : keys) {
                sectionYs[idx++] = sy;
            }
        }
        final int[] ys = sectionYs;

        Common.discardPendingSectionData(this.level.dimension(), cpos);

        this.level.getChunkSource().drop(cpos);

        this.level.queueLightUpdate(() -> {
            LevelLightEngine le = this.level.getLightEngine();
            le.setLightEnabled(cpos, false);
            if (ys != null) {
                for (int sy : ys) {
                    le.queueSectionData(LightLayer.BLOCK, SectionPos.of(cpos, sy), null);
                    le.queueSectionData(LightLayer.SKY, SectionPos.of(cpos, sy), null);
                }
                for (int sy : ys) {
                    le.updateSectionStatus(SectionPos.of(cpos, sy), true);
                }
            } else {
                for (int i = le.getMinLightSection(); i < le.getMaxLightSection(); i++) {
                    le.queueSectionData(LightLayer.BLOCK, SectionPos.of(cpos, i), null);
                    le.queueSectionData(LightLayer.SKY, SectionPos.of(cpos, i), null);
                }
                for (int j = this.level.getMinSectionY(); j <= this.level.getMaxSectionY(); j++) {
                    le.updateSectionStatus(SectionPos.of(cpos, j), true);
                }
            }
        });
    }

    @Overwrite
    private void enableChunkLight(LevelChunk chunk, int x, int z) {
        LevelLightEngine lightEngine = this.level.getChunkSource().getLightEngine();
        ChunkPos chunkPos = chunk.getPos();
        // 只更新光照状态并收集有内容的段，标脏延到循环外一次做。
        //
        // 区间由段集合取上下界，不按 chunk 的窗口取：客户端 chunk 的窗口在构造后、每帧拉正之前是构造
        // 默认的单段，取的是维度下界那一段，此刻容器里的真段全在窗口之外。按窗口判定会把整批标脏丢掉，
        // 新区块旁边的旧区块于是保留着「贴着空气」的接缝面，直到相机移动把它顺带重编。
        //
        // 空段不入集合：它们没有几何，到达也不改变任何邻居的面。
        WindowedChunk windowed = (WindowedChunk) chunk;
        IntArrayList ys = new IntArrayList();
        for (Map.Entry<Integer, LevelChunkSection> e : windowed.windowedAllSections().entrySet()) {
            LevelChunkSection section = e.getValue();
            if (section == null) {
                continue;
            }
            int sectionY = e.getKey();
            boolean air = section.hasOnlyAir();
            lightEngine.updateSectionStatus(SectionPos.of(chunkPos, sectionY), air);
            if (!air) {
                ys.add(sectionY);
            }
        }
        if (ys.isEmpty()) {
            return;
        }
        int minSy = Integer.MAX_VALUE;
        int maxSy = Integer.MIN_VALUE;
        for (int i = 0; i < ys.size(); i++) {
            int sy = ys.getInt(i);
            if (sy < minSy) {
                minSy = sy;
            }
            if (sy > maxSy) {
                maxSy = sy;
            }
        }
        // 跨度超过一个窗口时改逐段带邻居标脏：并集是历次窗口的并集，tp 后跨度可达上亿，而逐段标脏
        // 按段集合的条目数走，不按跨度走。
        int cap = FarlandsConfig.verticalSimulationDistance * 2 + 1;
        if (maxSy - minSy + 1 <= cap) {
            this.level.setSectionRangeDirty(x - 1, minSy - 1, z - 1, x + 1, maxSy + 1, z + 1);
            return;
        }
        for (int i = 0; i < ys.size(); i++) {
            this.level.setSectionDirtyWithNeighbors(x, ys.getInt(i), z);
        }
    }

    /**
     * FarLands 光照数据应用：chunk-with-light 包 RETURN 时把附加的光照载荷灌进客户端引擎。
     *
     * <p>
     * ClientboundLevelChunkWithLightPacketMixin 的 farlandsLightData 是 @Unique 私有字段，
     * 且该 mixin 在 main 源集——客户端经反射读取（与 1.21.1 同方式，低频：每 chunk 一次）。
     */
    private static final java.lang.reflect.Field FARLANDS_LIGHT_FIELD;
    static {
        try {
            FARLANDS_LIGHT_FIELD = ClientboundLevelChunkWithLightPacket.class
                    .getDeclaredField("farlandsLightData");
            FARLANDS_LIGHT_FIELD.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void onLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!(this.level.getLightEngine() instanceof FarLandsLightEngine fle)) {
            return;
        }
        try {
            FarLandsLightPacketData fd = (FarLandsLightPacketData) FARLANDS_LIGHT_FIELD.get(packet);
            if (fd != null) {
                fd.apply(fle, packet.getX(), packet.getZ());
            }
        } catch (Exception e) {
            // 不静默吞错：catch ignored 会掩盖 farlandsLightData 应用失败
            InfsFarlands.LOGGER.error("FLPKT apply EXCEPTION chunk={},{}", packet.getX(), packet.getZ(), e);
        }
        // §5 缓存补应用：chunk 加载完成（即 replaceWithPacketData 之后）→ 应用此前因
        // chunk 未加载而缓存的 §5 section 数据，防方块数据永久缺失——空缺/双端不同步。
        com.inf.farlands.client.register.packet.ChunkDataPacketRegister.applyPendingSectionData(
                this.level, packet.getX(), packet.getZ());
    }
}